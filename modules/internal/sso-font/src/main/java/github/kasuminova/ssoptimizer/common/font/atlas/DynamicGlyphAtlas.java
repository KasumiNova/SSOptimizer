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

public final class DynamicGlyphAtlas {
    private static final Logger LOGGER = Logger.getLogger(DynamicGlyphAtlas.class);

    public static final String PAGE_SIZE_PROPERTY  = "ssoptimizer.font.atlas.pageSize";
    public static final String MAX_PAGES_PROPERTY  = "ssoptimizer.font.atlas.maxPages";

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int DEFAULT_MAX_PAGES = 32;

    private final int pageSize;
    private final int maxPages;
    private final Map<GroupKey, Group> groups = new LinkedHashMap<>(16, 0.75f, true);
    private int totalPages;
    private volatile int textureGeneration;
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
                // 只有主录制线程可就地补建纹理，其他线程等待后续 flush，避免 allocate 切割正在录制的帧。
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

    public synchronized void flushDirty() {
        for (final Group group : groups.values()) {
            for (final GlyphAtlasPage page : group.pages) {
                page.flushDirty();
            }
        }
    }

    public synchronized void onContextRecreated() {
        for (final Group group : groups.values()) {
            for (final GlyphAtlasPage page : group.pages) {
                page.onContextRecreated();
            }
        }
        textureGeneration++;
    }

    /** 整组淘汰或上下文重建时递增，供调用方失效槽位缓存。 */
    public int textureGeneration() {
        return textureGeneration;
    }

    public synchronized int pageCount() {
        return totalPages;
    }

    public synchronized int groupCount() {
        return groups.size();
    }

    public synchronized int slotCount(final String faceKey, final int sizeBucketMillis, final int strokeBucketMillis) {
        final Group group = groups.get(new GroupKey(faceKey, sizeBucketMillis, strokeBucketMillis));
        return group == null ? 0 : group.slots.size();
    }

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

    private record GroupKey(String faceKey, int sizeBucketMillis, int strokeBucketMillis) {
    }

    private static final class Group {
        final List<GlyphAtlasPage>     pages = new ArrayList<>();
        final Map<Integer, GlyphSlot>  slots = new HashMap<>();
    }
}
