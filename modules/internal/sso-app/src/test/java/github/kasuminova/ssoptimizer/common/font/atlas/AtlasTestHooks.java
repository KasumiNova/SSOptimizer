package github.kasuminova.ssoptimizer.common.font.atlas;

import java.util.List;

/**
 * AtlasSoftwareRenderIT 的同包桥接：把图集实现层为测试放宽的 package-private
 * 访问点（{@link DynamicGlyphAtlas#pagesOfGroup} / {@link GlyphAtlasPage#stagingSnapshot}）
 * 暴露给 {@code common.font} 包的集成测试，避免把测试钩子提升为 public API。
 */
public final class AtlasTestHooks {
    private AtlasTestHooks() {
    }

    /** 指定 (face, 尺寸档, 描边档) 分组的页列表快照。 */
    public static List<GlyphAtlasPage> pagesOfGroup(final DynamicGlyphAtlas atlas,
                                                    final String faceKey,
                                                    final int sizeBucketMillis,
                                                    final int strokeBucketMillis) {
        return atlas.pagesOfGroup(faceKey, sizeBucketMillis, strokeBucketMillis);
    }

    /** 页的逻辑线程侧 staging 镜像快照（ALPHA8 字节，行优先）。 */
    public static byte[] stagingSnapshot(final GlyphAtlasPage page) {
        return page.stagingSnapshot();
    }
}
