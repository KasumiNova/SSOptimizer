package github.kasuminova.ssoptimizer.common.font.atlas;

import github.kasuminova.ssoptimizer.common.font.NativeGlyphBitmap;
import github.kasuminova.ssoptimizer.bridge.opengl.FontAtlasGl;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class GlyphAtlasPage {

    private final int        size;
    private final ByteBuffer staging;
    private final List<int[]> dirtyRects = new ArrayList<>();

    private int shelfY;
    private int shelfX;
    private int shelfHeight;

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

    public int textureId() {
        return textureId;
    }

    synchronized byte[] stagingSnapshot() {
        final ByteBuffer view = staging.duplicate();
        view.clear();
        final byte[] snapshot = new byte[view.capacity()];
        view.get(snapshot);
        return snapshot;
    }

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

    /** staging 使用 GL 行序，写入时必须垂直翻转以匹配 quad 的 UV 约定。 */
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

    public synchronized void flushDirty() {
        if (!GlDispatch.isMainRecordingThread() && !GlDispatch.isRenderThread()) {
            return;
        }
        ensureTexture();
        if (dirtyRects.isEmpty()) {
            return;
        }

        // 已写入的 cell 在整组淘汰前不可重写，上传命令可安全持有 staging 的定位视图。
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
        // 命令体不得直接引用 org.lwjgl，避免 RenderThreadRedirector 将真实上传再次改写为录制调用。
        GlDispatch.submit(() -> FontAtlasGl.uploadAlphaRects(texture, size, rects, payloads));
    }

    public synchronized void onContextRecreated() {
        textureId = 0;
        dirtyRects.clear();
        dirtyRects.add(new int[]{0, 0, size, size});
    }

    public synchronized void release() {
        final int texture = textureId;
        textureId = 0;
        dirtyRects.clear();
        if (texture != 0) {
            GlDispatch.submit(() -> FontAtlasGl.deleteTexture(texture));
        }
    }

    /** 复用前必须清零 staging，避免旧像素经整页回传残留到 cell 间隙并产生采样渗色。 */
    synchronized void resetForReuse() {
        shelfY = 0;
        shelfX = 0;
        shelfHeight = 0;
        dirtyRects.clear();
        final ByteBuffer view = staging.duplicate();
        view.clear();
        final byte[] zeros = new byte[8192];
        while (view.hasRemaining()) {
            view.put(zeros, 0, Math.min(zeros.length, view.remaining()));
        }
    }

    /** 仅主录制线程或渲染线程可调用，避免 allocate 切割其他线程正在录制的帧。 */
    public synchronized void ensureTexture() {
        if (textureId != 0) {
            return;
        }
        textureId = GlDispatch.allocate(this::createTexture);
        dirtyRects.clear();
        dirtyRects.add(new int[]{0, 0, size, size});
    }

    private int createTexture() {
        return FontAtlasGl.createAlphaTexture(size);
    }
}
