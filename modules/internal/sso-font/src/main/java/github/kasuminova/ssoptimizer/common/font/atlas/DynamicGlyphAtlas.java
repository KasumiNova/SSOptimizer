package github.kasuminova.ssoptimizer.common.font.atlas;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.font.NativeGlyphBitmap;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态字形图集：TTF 源字体的 (face, sizeBucket, strokeBucket) 分组槽位缓存。
 * <p>
 * 槽位 key = (faceKey, sizeBucketMillis, strokeBucketMillis, codePoint)；
 * 页按 (faceKey, sizeBucketMillis, strokeBucketMillis) 分组持有——同组字形
 * 同尺寸同描边档位，生命周期一致，LRU 以整组为粒度淘汰（组内页满开新页，
 * 全局页数超上限时淘汰最久未用的整组并释放其 GL 纹理）。
 * <p>
 * 未命中时同步调用 {@link GlyphRasterizer} 栅格化（FreeType 微秒级，可接受），
 * 分配 cell、写 staging、标脏后返回槽位；渲染线程上传由 {@link #flushDirty()}
 * （布局完成后、发射前调用）统一提交。
 * <p>
 * 线程安全：全部公开方法 synchronized——逻辑线程与 CJK 预热线程并发访问。
 */
public final class DynamicGlyphAtlas {
    private static final Logger LOGGER = Logger.getLogger(DynamicGlyphAtlas.class);

    /** 图集页边长（像素）：{@code -Dssoptimizer.font.atlas.pageSize}，默认 2048。 */
    public static final String PAGE_SIZE_PROPERTY  = "ssoptimizer.font.atlas.pageSize";
    /** 全局页数上限：{@code -Dssoptimizer.font.atlas.maxPages}，默认 32。 */
    public static final String MAX_PAGES_PROPERTY  = "ssoptimizer.font.atlas.maxPages";

    private static final int DEFAULT_PAGE_SIZE = 2048;
    // 覆盖表约 24 个字体 face，CJK 预热各占 1~4 页；上限低于预热组数会引发
    // LRU 整组抖动（淘汰→重建→整页重传）。32 页 = 128MB VRAM + 128MB direct
    // staging（构造时一次分配），两者均有界。
    private static final int DEFAULT_MAX_PAGES = 32;

    private final int pageSize;
    private final int maxPages;
    /** accessOrder LinkedHashMap：LRU 顺序即迭代顺序（ eldest = 最久未用）。 */
    private final Map<GroupKey, Group> groups = new LinkedHashMap<>(16, 0.75f, true);
    private int totalPages;
    /**
     * 纹理代际：整组淘汰（纹理释放）与上下文重建（纹理 id 归零重建）时递增。
     * 调用方（TtfGlyphProvider 的槽位直通缓存）据此判断缓存的 textureId 是否
     * 仍指向存活纹理——代际不变则槽位与纹理 id 恒有效。
     */
    private volatile int textureGeneration;
    /**
     * 淘汰页回收池：LRU 淘汰的页经 release + resetForReuse 后入池复用，避免
     * 每次淘汰都新分配 4MB direct staging（回收不依赖 GC，direct 占用有界）。
     * 池上限 = maxPages（超出丢弃回 GC）；池内页不计入 totalPages。
     */
    private final List<GlyphAtlasPage> recycledPages = new ArrayList<>();

    public DynamicGlyphAtlas() {
        this(configuredPageSize(), configuredMaxPages());
    }

    public DynamicGlyphAtlas(final int pageSize, final int maxPages) {
        if (pageSize <= 0 || maxPages <= 0) {
            throw new IllegalArgumentException("图集页尺寸与页数上限必须为正: pageSize=" + pageSize + " maxPages=" + maxPages);
        }
        this.pageSize = pageSize;
        this.maxPages = maxPages;
    }

    /**
     * 查/建字形槽位：命中直接返回；未命中同步栅格化、分配 cell、写 staging 并标脏。
     *
     * @param faceKey           字体身份（原版资源路径）
     * @param sizeBucketMillis  尺寸档位（千分位 bucketScale）
     * @param strokeBucketMillis 描边档位（设备像素千分位，0 = 纯填充）
     * @param codePoint         字符码点（调用方已完成语义转换）
     * @param baseline          基线（face 像素坐标系）
     * @param rasterizer        未命中时的栅格化回调
     * @return 槽位；栅格化失败或字形超页返回 {@code null}（调用方按缺失字形语义回退 '?'）
     */
    public synchronized GlyphSlot request(
            final String faceKey,
            final int sizeBucketMillis,
            final int strokeBucketMillis,
            final int codePoint,
            final int baseline,
            final GlyphRasterizer rasterizer) {
        final GroupKey groupKey = new GroupKey(faceKey, sizeBucketMillis, strokeBucketMillis);
        Group group = groups.get(groupKey);
        if (group != null) {
            final GlyphSlot hit = group.slots.get(codePoint);
            if (hit != null) {
                FontAtlasDiagnostics.recordHit();
                // 纹理 id 归零窗口（上下文重建后首帧 / 预热线程建槽未建纹理）：
                // 主录制线程命中时就地重建纹理——否则本帧 quad 会烘焙到 0 号纹理；
                // 非主录制线程不建（allocate 会切割主线程正在录制的帧），等主线程补齐
                if (hit.textureId() == 0 && GlDispatch.isMainRecordingThread()) {
                    hit.page().ensureTexture();
                }
                return hit;
            }
        }

        FontAtlasDiagnostics.recordMiss();
        final NativeGlyphBitmap bitmap =
                rasterizer.rasterize(codePoint, baseline, strokeBucketMillis / 1000.0f);
        if (bitmap == null || !bitmap.hasImage()) {
            FontAtlasDiagnostics.recordRasterizeFailure();
            return null;
        }

        if (group == null) {
            group = new Group();
            groups.put(groupKey, group);
        }

        GlyphAtlasPage page = group.pages.isEmpty() ? null : group.pages.get(group.pages.size() - 1);
        int[] cell = page == null ? null : page.allocateCell(bitmap.width(), bitmap.height());
        if (cell == null) {
            // 优先复用淘汰回收池中的页（staging direct 复用），池空才新分配
            page = recycledPages.isEmpty()
                    ? new GlyphAtlasPage(pageSize)
                    : recycledPages.remove(recycledPages.size() - 1);
            group.pages.add(page);
            totalPages++;
            FontAtlasDiagnostics.recordPageCreated();
            evictLeastRecentlyUsedGroups(groupKey);
            cell = page.allocateCell(bitmap.width(), bitmap.height());
            if (cell == null) {
                LOGGER.warn("[SSOptimizer] 字形超出图集页尺寸无法入图集: face=" + faceKey
                        + " codePoint=" + codePoint + " size=" + bitmap.width() + "x" + bitmap.height()
                        + " pageSize=" + pageSize);
                return null;
            }
        }

        page.writeBitmap(cell[0], cell[1], bitmap);
        final float invSize = 1.0f / pageSize;
        final GlyphSlot slot = new GlyphSlot(
                page, cell[0], cell[1], bitmap.width(), bitmap.height(),
                bitmap.xOffset(), bitmap.yOffset(),
                cell[0] * invSize, cell[1] * invSize,
                bitmap.width() * invSize, bitmap.height() * invSize);
        group.slots.put(codePoint, slot);
        return slot;
    }

    /**
     * 上传全部脏页（各 provider 布局完成后、发射前调用；空脏页无操作）。
     */
    public synchronized void flushDirty() {
        for (final Group group : groups.values()) {
            for (final GlyphAtlasPage page : group.pages) {
                page.flushDirty();
            }
        }
    }

    /**
     * GL 上下文重建回调（经 {@code GlDispatch.registerContextRecreatedListener} 注册）：
     * 全部页纹理 id 归零并整页标脏，槽位保留，下次 flushDirty 重建上传。
     */
    public synchronized void onContextRecreated() {
        for (final Group group : groups.values()) {
            for (final GlyphAtlasPage page : group.pages) {
                page.onContextRecreated();
            }
        }
        textureGeneration++;
    }

    /** 当前纹理代际（淘汰/上下文重建时递增）；provider 侧缓存的失效哨兵。 */
    public int textureGeneration() {
        return textureGeneration;
    }

    /** 当前页总数（诊断/测试）。 */
    public synchronized int pageCount() {
        return totalPages;
    }

    /** 当前分组数（诊断/测试）。 */
    public synchronized int groupCount() {
        return groups.size();
    }

    /** 指定分组的槽位数（测试）。 */
    public synchronized int slotCount(final String faceKey, final int sizeBucketMillis, final int strokeBucketMillis) {
        final Group group = groups.get(new GroupKey(faceKey, sizeBucketMillis, strokeBucketMillis));
        return group == null ? 0 : group.slots.size();
    }

    /**
     * 测试钩子（package-private，AtlasSoftwareRenderIT 经同包桥接类访问）：
     * 指定分组的页列表快照；无该组返回空表。
     */
    synchronized List<GlyphAtlasPage> pagesOfGroup(final String faceKey, final int sizeBucketMillis, final int strokeBucketMillis) {
        final Group group = groups.get(new GroupKey(faceKey, sizeBucketMillis, strokeBucketMillis));
        return group == null ? List.of() : List.copyOf(group.pages);
    }

    private static int configuredPageSize() {
        return Integer.parseInt(System.getProperty(PAGE_SIZE_PROPERTY, String.valueOf(DEFAULT_PAGE_SIZE)));
    }

    private static int configuredMaxPages() {
        return Integer.parseInt(System.getProperty(MAX_PAGES_PROPERTY, String.valueOf(DEFAULT_MAX_PAGES)));
    }

    /**
     * 全局页数超上限时按 LRU 淘汰最久未用的整组（跳过当前活跃组），
     * 释放其 GL 纹理并清槽位表。
     */
    private void evictLeastRecentlyUsedGroups(final GroupKey activeGroup) {
        boolean evicted = false;
        final Iterator<Map.Entry<GroupKey, Group>> iterator = groups.entrySet().iterator();
        while (totalPages > maxPages && iterator.hasNext()) {
            final Map.Entry<GroupKey, Group> eldest = iterator.next();
            if (eldest.getKey().equals(activeGroup)) {
                continue;
            }
            for (final GlyphAtlasPage page : eldest.getValue().pages) {
                page.release();
                page.resetForReuse();
                if (recycledPages.size() < maxPages) {
                    recycledPages.add(page);
                }
                FontAtlasDiagnostics.recordPageEvicted();
            }
            totalPages -= eldest.getValue().pages.size();
            iterator.remove();
            evicted = true;
            if (FontAtlasDiagnostics.isEnabled()) {
                LOGGER.info("[SSOptimizer] Font atlas evicted group " + eldest.getKey()
                        + " pages=" + eldest.getValue().pages.size() + " totalPages=" + totalPages);
            }
        }
        if (evicted) {
            textureGeneration++;
        }
    }

    /** 页分组 key：同组字形同 face、同尺寸档、同描边档，生命周期一致。 */
    private record GroupKey(String faceKey, int sizeBucketMillis, int strokeBucketMillis) {
    }

    /** 一组页 + 组内码点→槽位表。 */
    private static final class Group {
        final List<GlyphAtlasPage>     pages = new ArrayList<>();
        final Map<Integer, GlyphSlot>  slots = new HashMap<>();
    }
}
