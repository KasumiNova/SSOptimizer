package github.kasuminova.ssoptimizer.common.font.atlas;

import github.kasuminova.ssoptimizer.common.font.NativeGlyphBitmap;
import github.kasuminova.ssoptimizer.bridge.opengl.FontAtlasGl;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态字形图集的单页：N×N 的 GL_ALPHA8 纹理 + 逻辑线程侧的整页 direct staging 镜像。
 * <p>
 * 数据流：逻辑线程（或预热线程）经 {@link #allocateCell(int, int)} 做 shelf 行式分配、
 * {@link #writeBitmap} 写 staging 并记脏矩形；{@link #flushDirty()} 把脏矩形区域
 * 以「staging 步进视图」（零 direct 分配）捕获进 {@code GlCommand} 经
 * {@code GlDispatch.submit} 提交，渲染线程以 GL_UNPACK_ROW_LENGTH=页边长执行
 * glTexSubImage2D 上传。纹理 id 首用时经 {@code GlDispatch.allocate} 在渲染线程
 * 创建（glGenTextures + glTexImage2D 分配 + LINEAR/CLAMP_TO_EDGE）。
 * 全部真实 GL 调用收口在 bridge 包的 {@code FontAtlasGl}（该包被
 * {@code RenderThreadRedirector} 排除，裸 lwjgl 调用不会被二次重定向成录制调用）；
 * 本类与命令体内不得出现 org.lwjgl 引用。
 * <p>
 * direct 内存契约：staging 在构造时一次性 allocateDirect(size²)，flushDirty 的
 * payload 只是它的定位视图（duplicate，不占新 direct 配额）——页级 direct 占用
 * 恒有界（maxPages × size²），杜绝历史上「逐 rect allocateDirect 快照 +
 * DisableExplicitGC 下 Cleaner 回收不及时」导致的 direct memory OOM。
 * 视图快照的安全性来自 cell 不可变性：槽位一经写入从不重写（回收粒度是
 * (face, bucket) 整组淘汰），待执行上传命令晚读 staging 只会读到相同的
 * cell 数据， disjoint cell 之间不会撕裂。
 * <p>
 * 线程规则：纹理创建走 {@code GlDispatch.allocate} 阻塞资源通道（内部 swapFrames
 * 切割当前录制帧），只允许主录制线程发起——CJK 预热 daemon 线程的
 * {@link #writeBitmap} 仅写 staging + 标脏（textureId 保持 0），纹理由主录制
 * 线程的 {@link #flushDirty()} / 图集命中路径（{@link DynamicGlyphAtlas}）补齐。
 * <p>
 * cell 间留 1px padding 防双线性采样渗色；页内槽位一经分配不回收——
 * 回收粒度是 (face, bucket) 整组淘汰（{@link DynamicGlyphAtlas}），页随组释放。
 * <p>
 * 线程安全：全部公开方法 synchronized；textureId 为 volatile（布局线程在
 * 烘焙 quad 时直读，渲染线程命令体写入前必先经 allocate 同步返回）。
 */
public final class GlyphAtlasPage {

    private final int        size;
    private final ByteBuffer staging;
    private final List<int[]> dirtyRects = new ArrayList<>();

    /** shelf 分配游标：当前 shelf 的 y、x 与 shelf 高。 */
    private int shelfY;
    private int shelfX;
    private int shelfHeight;

    /** GL 纹理 id；0 = 尚未创建或上下文重建后待重建。 */
    private volatile int textureId;

    public GlyphAtlasPage(final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("图集页尺寸必须为正: " + size);
        }
        this.size = size;
        this.staging = ByteBuffer.allocateDirect(size * size);
    }

    public int size() {
        return size;
    }

    /** GL 纹理 id（0 = 未创建/待重建）；布局线程烘焙 quad 时直读。 */
    public int textureId() {
        return textureId;
    }

    /**
     * 测试钩子（package-private，AtlasSoftwareRenderIT 经同包桥接类访问）：
     * 逻辑线程侧全页 staging 镜像的快照（ALPHA8 字节，行优先）。
     */
    synchronized byte[] stagingSnapshot() {
        final ByteBuffer view = staging.duplicate();
        view.clear();
        final byte[] snapshot = new byte[view.capacity()];
        view.get(snapshot);
        return snapshot;
    }

    /**
     * shelf 行式分配一个 w×h 的 cell（cell 间 1px padding）。
     *
     * @return {x, y}；页满或字形超页返回 {@code null}
     */
    public synchronized int[] allocateCell(final int width, final int height) {
        if (width <= 0 || height <= 0 || width > size || height > size) {
            return null;
        }
        if (shelfX + width > size) {
            shelfY += shelfHeight + 1;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + height > size) {
            return null;
        }
        final int[] cell = {shelfX, shelfY};
        shelfX += width + 1;
        shelfHeight = Math.max(shelfHeight, height);
        return cell;
    }

    /**
     * 把栅格化位图写入 staging（取 alpha 通道），并把覆盖区域记入脏矩形队列。
     * 主录制线程首写时即经 {@link #ensureTexture()} 创建纹理——布局线程在
     * 槽位返回后即把 textureId 烘焙进 quad，创建不能推迟到 flush（否则首帧
     * /上下文重建后首帧烘焙到 0）；非主录制线程（CJK 预热 daemon）不建纹理
     * （allocate 内部 swapFrames 会切割主线程正在录制的帧），仅写 staging +
     * 标脏，textureId 保持 0，由主录制线程的 flush/命中路径补齐。
     * 行序约定：staging 直接映射 GL 纹理行序（行 0 = v 0），而引擎 quad 约定
     * 顶边顶点采样大 v（texY+texH 一侧），即 v 0 = 字形底部——因此写入时按行
     * 垂直翻转（源行 0 = 字形顶 → staging 行 y+h-1）。位图路径的游戏 fnt 页
     * 遵循同一 GL 约定，此处对齐之。
     */
    public synchronized void writeBitmap(final int x, final int y, final NativeGlyphBitmap bitmap) {
        if (GlDispatch.isMainRecordingThread()) {
            ensureTexture();
        }
        final int w = bitmap.width();
        final int h = bitmap.height();
        final int[] argb = bitmap.argbPixels();
        final byte[] rowBuffer = new byte[w];
        for (int row = 0; row < h; row++) {
            final int srcRow = row * w;
            for (int col = 0; col < w; col++) {
                rowBuffer[col] = (byte) (argb[srcRow + col] >>> 24);
            }
            staging.put((y + h - 1 - row) * size + x, rowBuffer, 0, w);
        }
        dirtyRects.add(new int[]{x, y, w, h});
    }

    /**
     * 把脏矩形提交渲染线程上传并清空脏队列（空队列无操作）。
     * 上下文重建后的首次 flush（无新写入、整页已标脏）在此重建纹理并全量回传。
     * <p>
     * payload 是 staging 的定位视图（duplicate + position 到矩形原点），上传侧以
     * GL_UNPACK_ROW_LENGTH=size 跨行读取——零 direct 分配、零拷贝。安全性：cell
     * 一经写入不可变（回收粒度为整组淘汰），待执行命令晚读 staging 不会撕裂；
     * 视图持有 staging 引用，页淘汰后待执行命令也不会读到已释放内存。
     * <p>
     * 线程前提：只应由主录制线程经 {@code GlyphProvider.flushPendingUploads()}
     * 调用（或渲染线程自身）。防御：非主录制线程且非渲染线程调用时只保留脏
     * 标记，不建纹理不提交——纹理创建走阻塞资源通道，非主录制线程发起会切割
     * 主线程正在录制的帧（预热线程写入的数据等主线程 flush 时一并上传）。
     */
    public synchronized void flushDirty() {
        if (!GlDispatch.isMainRecordingThread() && !GlDispatch.isRenderThread()) {
            return;
        }
        ensureTexture();
        if (dirtyRects.isEmpty()) {
            return;
        }

        // payload = staging 步进视图（命令体捕获视图，staging 后续写入的 cell
        // 与已捕获矩形区域不相交，见类 javadoc 的 cell 不可变契约）
        final int texture = textureId;
        final List<int[]> rects = new ArrayList<>(dirtyRects);
        dirtyRects.clear();
        final List<ByteBuffer> payloads = new ArrayList<>(rects.size());
        long bytes = 0;
        for (final int[] rect : rects) {
            final ByteBuffer view = staging.duplicate();
            view.position(rect[1] * size + rect[0]);
            view.limit(view.capacity());
            payloads.add(view);
            bytes += (long) rect[2] * rect[3];
        }
        FontAtlasDiagnostics.recordUpload(bytes, rects.size());
        // 命令体不引用 org.lwjgl：真实 GL 调用收口在 bridge 包的 FontAtlasGl
        // （RenderThreadRedirector 排除包），避免被二次重定向成录制调用
        GlDispatch.submit(() -> FontAtlasGl.uploadAlphaRects(texture, size, rects, payloads));
    }

    /**
     * GL 上下文重建回调（回调线程不定，内部已同步）：纹理 id 归零 + 整页标脏；
     * 槽位与 staging 数据保留，下次 {@link #flushDirty()} 在新上下文中重建并全量回传。
     */
    public synchronized void onContextRecreated() {
        textureId = 0;
        dirtyRects.clear();
        dirtyRects.add(new int[]{0, 0, size, size});
    }

    /**
     * 释放本页 GL 纹理（(face, bucket) 整组 LRU 淘汰时调用）：删除命令经
     * GlDispatch 按提交流顺序执行——同帧内先于本命令发射的 quad 引用旧 id 不受影响，
     * 之后的渲染应由新组的新槽位承接。
     */
    public synchronized void release() {
        final int texture = textureId;
        textureId = 0;
        dirtyRects.clear();
        if (texture != 0) {
            GlDispatch.submit(() -> FontAtlasGl.deleteTexture(texture));
        }
    }

    /**
     * 淘汰页回收复用（{@link DynamicGlyphAtlas} 页池，{@code release()} 之后调用）：
     * 清 shelf 分配游标与脏队列，staging direct 缓冲保留复用——页 staging 为
     * 4MB direct 内存，淘汰即丢弃会让回收依赖 GC Cleaner（ZGC + DisableExplicitGC
     * 下无保障，长程运行堆积至 OOM）；复用使 direct 占用恒有界（≤ maxPages 在役
     * + maxPages 池内）。staging 旧像素保留无害：复用方首次 ensureTexture 整页
     * 标脏全量回传，旧 cell 区域无任何存活槽位/quad 引用。
     */
    synchronized void resetForReuse() {
        shelfY = 0;
        shelfX = 0;
        shelfHeight = 0;
        dirtyRects.clear();
    }

    /**
     * 纹理未创建时（首次写入 / 上下文重建后）经阻塞资源通道在渲染线程创建，
     * 并把整页标脏——新纹理不含此前任何 staging 内容，必须全量回传。
     * <p>
     * 线程前提：只允许主录制线程（或渲染线程）调用；非主录制线程调用会经
     * allocate 内部的 swapFrames 切割主线程正在录制的帧（预热路径规则见
     * {@link #writeBitmap}）。
     */
    public synchronized void ensureTexture() {
        if (textureId != 0) {
            return;
        }
        textureId = GlDispatch.allocate(this::createTexture);
        dirtyRects.clear();
        dirtyRects.add(new int[]{0, 0, size, size});
    }

    /** 渲染线程执行：创建 N×N GL_ALPHA8 纹理（LINEAR / CLAMP_TO_EDGE）。 */
    private int createTexture() {
        return FontAtlasGl.createAlphaTexture(size);
    }
}
