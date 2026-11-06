package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.starfarer.prototype.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcStripRenderHelperTest {

    private static final float FULL_CIRCLE_SPAN =
            Utils.normalizeAngle((float) Math.toRadians(360.0) - (float) Math.toRadians(0.0));

    @BeforeEach
    void isolateStaticCache() {
        ArcStripRenderHelper.clearCache();
    }

    private static float[] vanillaDirections(float step, int count) {
        float[] result = new float[(count + 1) * 2];
        for (float f = 0.0F; f < count + 1; f++) {
            float angle = step * f;
            int i = (int) f;
            result[i * 2] = (float) Math.cos(angle);
            result[i * 2 + 1] = (float) Math.sin(angle);
        }
        return result;
    }

    private static void assertDirectionsBitwiseEqual(float[] expected, float[] actual, String context) {
        assertEquals(expected.length, actual.length, context + ": 方向表长度不一致");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]),
                    context + ": 第 " + i + " 个分量必须与即时计算位级一致");
        }
    }

    @Test
    void directionsMatchImmediateTrigonometryBitwise() {
        float[] spans = {FULL_CIRCLE_SPAN, 1.0F, 3.7F, 0.5F, 2.0F};
        for (float span : spans) {
            for (int count : new int[]{36, 40, 52, 104, 256, 1024}) {
                float step = span / count;
                float[] cached = ArcStripRenderHelper.directions(step, count);
                assertDirectionsBitwiseEqual(vanillaDirections(step, count), cached,
                        "span=" + span + " count=" + count);
            }
        }
    }

    @Test
    void cachedVertexSequenceMatchesVanillaLoop() {
        float radius = 137.0F;
        float width = 1.75F;
        float count = ArcStripRenderHelper.arcSegmentCount(FULL_CIRCLE_SPAN, radius, 6.0F);
        float step = FULL_CIRCLE_SPAN / count;
        float midRadius = radius - width / 2.0F;

        float[] directions = ArcStripRenderHelper.directions(step, count);
        for (float f = 0.0F; f < count + 1; f++) {
            int i = (int) f;
            float angle = step * f;
            float expectedX = (float) Math.cos(angle) * midRadius;
            float expectedY = (float) Math.sin(angle) * midRadius;
            assertEquals(Float.floatToRawIntBits(expectedX),
                    Float.floatToRawIntBits(directions[i * 2] * midRadius),
                    "顶点 " + i + " X 必须位级一致");
            assertEquals(Float.floatToRawIntBits(expectedY),
                    Float.floatToRawIntBits(directions[i * 2 + 1] * midRadius),
                    "顶点 " + i + " Y 必须位级一致");
        }
    }

    @Test
    void intIndexEqualsFloatLoopCounter() {
        float counter = 0.0F;
        for (int i = 0; i <= 1_000_000; i++) {
            assertEquals(Float.floatToRawIntBits((float) i), Float.floatToRawIntBits(counter),
                    "float 循环计数器在第 " + i + " 次迭代与 int 索引分歧");
            counter += 1.0F;
        }
    }

    @Test
    void sameKeyReturnsSameInstanceDistinctKeysCreateEntries() {
        float stepA = FULL_CIRCLE_SPAN / 36.0F;
        float[] first = ArcStripRenderHelper.directions(stepA, 36.0F);
        float[] second = ArcStripRenderHelper.directions(stepA, 36.0F);
        assertSame(first, second, "同 (step, count) 必须复用同一缓存条目");

        int sizeBefore = ArcStripRenderHelper.cacheSize();
        ArcStripRenderHelper.directions(FULL_CIRCLE_SPAN / 40.0F, 40.0F);
        ArcStripRenderHelper.directions(1.0F / 36.0F, 36.0F);
        assertEquals(sizeBefore + 2, ArcStripRenderHelper.cacheSize(),
                "不同 step 或不同 count 必须产生独立缓存条目");
    }

    @Test
    void arcSegmentCountMatchesVanillaRounding() {
        for (float radius = 1.0F; radius <= 2000.0F; radius += 0.5F) {
            float expected = (float) Math.ceil(FULL_CIRCLE_SPAN * radius / 6.0F);
            if ((int) expected % 4 != 0) {
                expected = (int) expected / 4 * 4 + 4;
            }
            assertEquals(Float.floatToRawIntBits(expected),
                    Float.floatToRawIntBits(ArcStripRenderHelper.arcSegmentCount(FULL_CIRCLE_SPAN, radius, 6.0F)),
                    "radius=" + radius + " 段数推导必须与原版一致");
        }
    }

    @Test
    void lineArcSegmentCountMatchesVanillaRounding() {
        for (float radius = 1.0F; radius <= 500.0F; radius += 3.0F) {
            float base = (float) Math.ceil(FULL_CIRCLE_SPAN * radius / 6.0F);
            float expectedPlain = base;
            if ((int) expectedPlain % 4 != 0) {
                expectedPlain = (int) expectedPlain / 4 * 4 + 4;
            }
            assertEquals(Float.floatToRawIntBits(expectedPlain),
                    Float.floatToRawIntBits(
                            ArcStripRenderHelper.lineArcSegmentCount(FULL_CIRCLE_SPAN, radius, 6.0F, 1, false)),
                    "radius=" + radius + " 实线段数推导必须与原版一致");

            for (int dash : new int[]{1, 3, 6, 12}) {
                float expectedDash = base;
                if ((int) expectedDash % dash != 0) {
                    expectedDash = (int) expectedDash / dash * dash + dash;
                }
                assertEquals(Float.floatToRawIntBits(expectedDash),
                        Float.floatToRawIntBits(ArcStripRenderHelper.lineArcSegmentCount(
                                FULL_CIRCLE_SPAN, radius, 6.0F, dash, true)),
                        "radius=" + radius + " dash=" + dash + " 虚线段数推导必须与原版一致");
            }
        }
    }

    @Test
    void dashSegmentsNormalizationClampsNonPositive() {
        assertEquals(1, ArcStripRenderHelper.normalizeDashSegments(0));
        assertEquals(1, ArcStripRenderHelper.normalizeDashSegments(-7));
        assertEquals(5, ArcStripRenderHelper.normalizeDashSegments(5));
    }

    @Test
    void negativeCountYieldsZeroVertices() {
        float[] directions = ArcStripRenderHelper.directions(1.0F, -3.0F);
        assertEquals(0, directions.length, "负段数必须等价于原版的零顶点输出");
    }

    @Test
    void cacheCapStopsGrowthButKeepsResultsCorrect() {
        for (int i = 0; i < ArcStripRenderHelper.MAX_ENTRIES + 100; i++) {
            ArcStripRenderHelper.directions(1.0F + i * 0.001F, 36.0F);
        }
        assertTrue(ArcStripRenderHelper.cacheSize() <= ArcStripRenderHelper.MAX_ENTRIES,
                "缓存条目数不得超过上限");

        float step = 9876.5F;
        float[] uncached = ArcStripRenderHelper.directions(step, 36.0F);
        assertDirectionsBitwiseEqual(vanillaDirections(step, 36), uncached, "满缓存后的新键");
    }
}
