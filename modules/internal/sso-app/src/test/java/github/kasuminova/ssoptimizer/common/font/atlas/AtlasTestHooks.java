package github.kasuminova.ssoptimizer.common.font.atlas;

import java.util.List;

public final class AtlasTestHooks {
    private AtlasTestHooks() {
    }

    public static List<GlyphAtlasPage> pagesOfGroup(final DynamicGlyphAtlas atlas,
                                                    final String faceKey,
                                                    final int sizeBucketMillis,
                                                    final int strokeBucketMillis) {
        return atlas.pagesOfGroup(faceKey, sizeBucketMillis, strokeBucketMillis);
    }

    public static byte[] stagingSnapshot(final GlyphAtlasPage page) {
        return page.stagingSnapshot();
    }
}
