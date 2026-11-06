package github.kasuminova.ssoptimizer.common.campaign;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalVisibilityPrefilterTest {

    @Test
    void parseEnabledDefaultsAndFallback() {
        assertFalse(TacticalVisibilityPrefilter.parseEnabled(null), "未设置时默认关闭");
        assertTrue(TacticalVisibilityPrefilter.parseEnabled("true"));
        assertTrue(TacticalVisibilityPrefilter.parseEnabled("TRUE"));
        assertFalse(TacticalVisibilityPrefilter.parseEnabled("false"));
        assertFalse(TacticalVisibilityPrefilter.parseEnabled("1"), "非法取值回退默认关闭");
    }

    @Test
    void boundaryAtExactMaxRangeIsNotFiltered() {
        final float sensorMax = 3000.0F;
        final float ext = 500.0F;
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                3500.0F, 0.0F, 0.0F, sensorMax, ext));
        assertTrue(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                Math.nextUp(3500.0F), 0.0F, 0.0F, sensorMax, ext));
    }

    @Test
    void radiusSubtractionAndClampMatchVanilla() {
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                100.0F, 150.0F, 50.0F, 0.0F, 0.0F));
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                200.0F, 150.0F, 50.0F, 0.0F, 0.0F));
        assertTrue(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                201.0F, 150.0F, 50.0F, 0.0F, 0.0F));
    }

    @Test
    void extendedDetectedAtRangeWidensThreshold() {
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                100_000.0F, 0.0F, 0.0F, 3000.0F, 200_000.0F));
        assertTrue(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                100_000.0F, 0.0F, 0.0F, 3000.0F, 1000.0F));
    }

    @Test
    void fuzzMatchesVanillaNoneExitFormula() {
        final Random random = new Random(20260904L);
        for (int trial = 0; trial < 10_000; trial++) {
            final float dist = random.nextFloat() * 50_000.0F;
            final float targetRadius = random.nextFloat() * 500.0F;
            final float viewerRadius = random.nextFloat() * 500.0F;
            final float sensorMax = random.nextFloat() < 0.5F
                    ? 3000.0F : random.nextFloat() * 10_000.0F;
            final float ext = random.nextFloat() < 0.8F
                    ? 0.0F : random.nextFloat() * 20_000.0F;

            assertEquals(vanillaNoneByDistance(dist, targetRadius, viewerRadius, sensorMax, ext),
                    TacticalVisibilityPrefilter.isDefinitelyInvisible(
                            dist, targetRadius, viewerRadius, sensorMax, ext),
                    "trial=" + trial + "：过滤判定必须与原版距离 NONE 出口一致");
        }
    }

    private static boolean vanillaNoneByDistance(final float dist, final float targetRadius,
                                                 final float viewerRadius, final float sensorMax,
                                                 final float ext) {
        float adjusted = dist - targetRadius - viewerRadius;
        if (adjusted < 0.0F) {
            adjusted = 0.0F;
        }
        return adjusted > sensorMax + ext;
    }
}
