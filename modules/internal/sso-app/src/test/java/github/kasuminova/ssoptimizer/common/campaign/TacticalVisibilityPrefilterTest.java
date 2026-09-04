package github.kasuminova.ssoptimizer.common.campaign;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TacticalVisibilityPrefilter} 的逻辑验证。
 * <p>
 * 核心验证：粗过滤判定与原版 {@code BaseCampaignEntity.getVisibilityLevelTo}
 * 的距离 NONE 出口逐字等价——凡被过滤的舰队在精判下必为不可见（NONE），
 * 凡未被过滤的舰队精判不会因距离出口返回 NONE。
 * 原版判定式（named 源码实证）：adjusted = max(0, centerDist - targetRadius - viewerRadius)；
 * adjusted &gt; sensorRangeMax + extendedDetectedAtRange 时返回 NONE（严格大于）。
 */
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
        // adjusted == sensorRangeMax + ext：原版是严格大于才 NONE，边界值必须保留
        final float sensorMax = 3000.0F;
        final float ext = 500.0F;
        // adjusted = dist - 半径和 = 3500 == 3500 → 不过滤
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                3500.0F, 0.0F, 0.0F, sensorMax, ext));
        // 略超过 → 过滤
        assertTrue(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                Math.nextUp(3500.0F), 0.0F, 0.0F, sensorMax, ext));
    }

    @Test
    void radiusSubtractionAndClampMatchVanilla() {
        // 原版：centerDist - targetRadius - viewerRadius，负值钳 0
        // dist 100，半径和 200 → adjusted 钳 0 → 任何非负 sensorMax 下都不可过滤
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                100.0F, 150.0F, 50.0F, 0.0F, 0.0F));
        // sensorMax 为 0 且 adjusted 为 0：0 > 0 为 false → 不过滤
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                200.0F, 150.0F, 50.0F, 0.0F, 0.0F));
        // sensorMax 为 0、adjusted 为 1：1 > 0 → 过滤
        assertTrue(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                201.0F, 150.0F, 50.0F, 0.0F, 0.0F));
    }

    @Test
    void extendedDetectedAtRangeWidensThreshold() {
        // ext 为被观察舰队动态值（模组可设任意大）：过滤阈值必须逐项计入
        assertFalse(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                100_000.0F, 0.0F, 0.0F, 3000.0F, 200_000.0F));
        assertTrue(TacticalVisibilityPrefilter.isDefinitelyInvisible(
                100_000.0F, 0.0F, 0.0F, 3000.0F, 1000.0F));
    }

    @Test
    void fuzzMatchesVanillaNoneExitFormula() {
        // 用原版判定式复刻件做全量对比：helper 结果必须与复刻件逐点一致
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

    /**
     * 原版 {@code getVisibilityLevelTo} 距离出口复刻
     * （named 源码 BaseCampaignEntity :1178-1186）。
     */
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
