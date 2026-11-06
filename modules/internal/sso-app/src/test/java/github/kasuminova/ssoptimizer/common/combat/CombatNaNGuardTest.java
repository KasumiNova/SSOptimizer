package github.kasuminova.ssoptimizer.common.combat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatNaNGuardTest {

    @BeforeEach
    void resetThrottle() {
        CombatNaNGuard.resetThrottle();
        CombatNaNGuard.SUMMARY_INTERVAL_MS = 60_000L;
    }

    @AfterEach
    void restoreInterval() {
        CombatNaNGuard.SUMMARY_INTERVAL_MS = 60_000L;
        CombatNaNGuard.resetThrottle();
    }

    @Test
    void isBadCoversNaNAndBothInfinities() {
        assertTrue(CombatNaNGuard.isBad(Float.NaN));
        assertTrue(CombatNaNGuard.isBad(Float.POSITIVE_INFINITY));
        assertTrue(CombatNaNGuard.isBad(Float.NEGATIVE_INFINITY));
        assertFalse(CombatNaNGuard.isBad(0.0F));
        assertFalse(CombatNaNGuard.isBad(-1.0F));
        assertFalse(CombatNaNGuard.isBad(Float.MAX_VALUE));
        assertFalse(CombatNaNGuard.isBad(Float.MIN_VALUE));
    }

    @Test
    void anyBadCoversStageTwoDecisionInputs() {
        assertFalse(CombatNaNGuard.anyBad(1.0F, 2.0F, 3.0F), "全有限值必须放行");
        assertTrue(CombatNaNGuard.anyBad(1.0F, Float.NaN));
        assertTrue(CombatNaNGuard.anyBad(Float.POSITIVE_INFINITY, 1.0F));
        assertTrue(CombatNaNGuard.anyBad(1.0F, Float.NEGATIVE_INFINITY, 2.0F));
        float zeroTimesInf = 0.0F * Float.POSITIVE_INFINITY;
        assertTrue(CombatNaNGuard.isBad(zeroTimesInf), "0*Inf=NaN 必须被拦截");
        assertTrue(CombatNaNGuard.anyBad(5.0F, zeroTimesInf));
        assertFalse(CombatNaNGuard.anyBad(), "空输入视为无坏值");
    }

    @Test
    void fullSamplesLimitedPerSignature() {
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.NOTICE, hitViaHelperA(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.SUPPRESS, hitViaHelperA(new Object()));

        Map<String, CombatNaNGuard.StatsSnapshot> stats = CombatNaNGuard.snapshotStats();
        assertEquals(1, stats.size(), "同一调用链必须聚成一个签名");
        CombatNaNGuard.StatsSnapshot s = only(stats);
        assertEquals(5, s.total, "每次坏值命中都必须计入总次数");
        assertEquals(3, s.samples);
        assertTrue(s.noticed);
        assertEquals(1, s.suppressed);
    }

    @Test
    void sameEntitySampleDeduplicated() {
        Object key = new Object();
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(key));
        assertEquals(CombatNaNGuard.ReportLevel.SUPPRESS, hitViaHelperA(key), "同实体重复命中只计数");
        assertEquals(CombatNaNGuard.ReportLevel.SUPPRESS, hitViaHelperA(key));

        CombatNaNGuard.StatsSnapshot s = only(CombatNaNGuard.snapshotStats());
        assertEquals(3, s.total);
        assertEquals(1, s.samples, "同一实体只占一个样本名额");
        assertEquals(2, s.suppressed);
    }

    @Test
    void differentChainsIsolated() {
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.NOTICE, hitViaHelperA(new Object()));

        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperB(new Object()), "另一条签名配额独立");
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperB(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperB(new Object()));
        assertEquals(CombatNaNGuard.ReportLevel.NOTICE, hitViaHelperB(new Object()));

        Map<String, CombatNaNGuard.StatsSnapshot> stats = CombatNaNGuard.snapshotStats();
        assertEquals(2, stats.size(), "两条调用链必须分成两个签名");
        for (CombatNaNGuard.StatsSnapshot s : stats.values()) {
            assertEquals(4, s.total);
            assertEquals(3, s.samples);
        }
    }

    @Test
    void differentSitesIsolatedEvenOnSameChain() {
        assertEquals(CombatNaNGuard.ReportLevel.FULL,
                CombatNaNGuard.hit(CombatNaNGuard.SITE_FLUX, new Object(), "s"));
        assertEquals(CombatNaNGuard.ReportLevel.FULL,
                CombatNaNGuard.hit(CombatNaNGuard.SITE_DAMAGE, new Object(), "s"), "同链不同检查点配额独立");
        assertEquals(2, CombatNaNGuard.snapshotStats().size());
    }

    @Test
    void periodicSummaryMarksSummarizedWatermark() {
        CombatNaNGuard.SUMMARY_INTERVAL_MS = 0L;
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        CombatNaNGuard.StatsSnapshot s = only(CombatNaNGuard.snapshotStats());
        assertEquals(1, s.total);
        assertEquals(s.total, s.summarizedTotal, "周期到点必须输出汇总并推进水位");

        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        s = only(CombatNaNGuard.snapshotStats());
        assertEquals(2, s.summarizedTotal);
    }

    @Test
    void summaryNotEmittedBeforeInterval() {
        hitViaHelperA(new Object());
        CombatNaNGuard.StatsSnapshot s = only(CombatNaNGuard.snapshotStats());
        assertEquals(0, s.summarizedTotal, "周期未到不得输出汇总");
    }

    private CombatNaNGuard.ReportLevel hitViaHelperA(final Object key) {
        return CombatNaNGuard.hit(CombatNaNGuard.SITE_FLUX, key, "sampleA");
    }

    private CombatNaNGuard.ReportLevel hitViaHelperB(final Object key) {
        return CombatNaNGuard.hit(CombatNaNGuard.SITE_FLUX, key, "sampleB");
    }

    private static CombatNaNGuard.StatsSnapshot only(final Map<String, CombatNaNGuard.StatsSnapshot> stats) {
        assertEquals(1, stats.size());
        return stats.values().iterator().next();
    }
}
