package github.kasuminova.ssoptimizer.common.render.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AtlasUvMapperTest {

    private static final float SRC_W = 256.0f;
    private static final float SRC_H = 256.0f;
    private static final int ATLAS_SIZE = 1024;
    private static final int REGION_X = 512;
    private static final int REGION_Y = 768;

    @Test
    void remapFromOriginProducesAtlasUv() {
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        assertEquals(0.5f, uv.texX(), 1e-6f);
        assertEquals(0.75f, uv.texY(), 1e-6f);
        assertEquals(0.125f, uv.texWidth(), 1e-6f);
        assertEquals(0.125f, uv.texHeight(), 1e-6f);
        assertEquals(0.00025f, uv.insetU(), 1e-6f);
        assertEquals(0.00025f, uv.insetV(), 1e-6f);
    }

    @Test
    void remapFromOriginHonorsNonZeroOriginUv() {
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.25f, 0.5f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        assertEquals(0.5625f, uv.texX(), 1e-6f);
        assertEquals(0.875f, uv.texY(), 1e-6f);
    }

    @Test
    void reDerivationFromCachedOriginIsIdempotent() {
        AtlasUvMapper.RemappedUv first = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);
        AtlasUvMapper.RemappedUv second = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        assertEquals(first.texX(), second.texX(), 1e-6f);
        assertEquals(first.texY(), second.texY(), 1e-6f);
        assertEquals(first.texWidth(), second.texWidth(), 1e-6f);
        assertEquals(first.texHeight(), second.texHeight(), 1e-6f);
    }

    @Test
    void reMappingAtlasUvAsOriginShiftsIntoAdjacentRegion() {
        AtlasUvMapper.RemappedUv first = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);
        AtlasUvMapper.RemappedUv buggy = AtlasUvMapper.remapFromOrigin(
                first.texX(), first.texY(), first.texWidth(), first.texHeight(),
                SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        assertNotEquals(first.texX(), buggy.texX(), 1e-6f);
        assertNotEquals(first.texY(), buggy.texY(), 1e-6f);
        assertEquals(0.625f, buggy.texX(), 1e-6f);
    }

    @Test
    void remapFromOriginExposesAccessorConversionParams() {
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        assertEquals(0.5f, uv.originU(), 1e-6f);
        assertEquals(0.75f, uv.originV(), 1e-6f);
        assertEquals(0.25f, uv.scaleU(), 1e-6f);
        assertEquals(0.25f, uv.scaleV(), 1e-6f);
        assertEquals(uv.originU(), AtlasUvMapper.originalToAtlas(uv.originU(), uv.scaleU(), 0.0f), 1e-6f);
    }

    @Test
    void accessorConversionRoundTrips() {
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.25f, 0.5f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        float atlasX = AtlasUvMapper.originalToAtlas(uv.originU(), uv.scaleU(), 0.25f);
        assertEquals(uv.texX(), atlasX, 1e-6f);
        assertEquals(0.25f, AtlasUvMapper.atlasToOriginal(uv.originU(), uv.scaleU(), atlasX), 1e-6f);

        float atlasW = AtlasUvMapper.originalToAtlas(0.0f, uv.scaleU(), 0.5f);
        assertEquals(uv.texWidth(), atlasW, 1e-6f);
        assertEquals(0.5f, AtlasUvMapper.atlasToOriginal(0.0f, uv.scaleU(), atlasW), 1e-6f);
    }

    @Test
    void modWrittenOriginalSpaceUvStaysInsideRegion() {
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 1.0f, 1.0f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        float writtenWidth = AtlasUvMapper.originalToAtlas(0.0f, uv.scaleU(), 1.0f);
        assertEquals(uv.texWidth(), writtenWidth, 1e-6f);
        assertEquals(uv.originU() + uv.scaleU(), uv.originU() + writtenWidth, 1e-6f);

        assertEquals(1.0f, AtlasUvMapper.atlasToOriginal(0.0f, uv.scaleU(), writtenWidth), 1e-6f);

        assertEquals(0.0f, AtlasUvMapper.originalToAtlas(0.0f, uv.scaleU(), 0.0f), 1e-6f);
    }
}
