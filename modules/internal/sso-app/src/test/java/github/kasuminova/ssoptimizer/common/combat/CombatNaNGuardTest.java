package github.kasuminova.ssoptimizer.common.combat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CombatNaNGuard} 核心判定与「调用链签名」节流逻辑验证。
 * <p>
 * 游戏实体（CollisionEntity/Damage）无法脱离引擎实例化，实体级方法由
 * NaNGuardMixinInjectionTest 以字节码核验覆盖注入正确性；本测试直接调用
 * 纯逻辑入口验证数值判定与签名节流语义。签名取自真实调用栈——helperA/helperB
 * 两个入口方法制造两条不同的调用链，同一入口的重复调用天然同签名。
 */
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
        // 第二阶段守卫判定核心：有限/NaN/+Inf/-Inf/0*Inf 全谱系
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
        // 同一调用链签名：前 3 个不同实体样本完整取证，第 4 个抑制通告，之后只计数
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
        // helperA 与 helperB 构成两条不同调用链：配额相互独立
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
        CombatNaNGuard.SUMMARY_INTERVAL_MS = 0L; // 立即到期的汇总周期
        assertEquals(CombatNaNGuard.ReportLevel.FULL, hitViaHelperA(new Object()));
        CombatNaNGuard.StatsSnapshot s = only(CombatNaNGuard.snapshotStats());
        assertEquals(1, s.total);
        assertEquals(s.total, s.summarizedTotal, "周期到点必须输出汇总并推进水位");

        // 无新命中不重复汇总：水位不动时再次命中才推进
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

    /** 调用链 A 入口：独立方法帧使经此处的命中聚为同一签名。 */
    private CombatNaNGuard.ReportLevel hitViaHelperA(final Object key) {
        return CombatNaNGuard.hit(CombatNaNGuard.SITE_FLUX, key, "sampleA");
    }

    /** 调用链 B 入口：与 A 不同的首业务帧。 */
    private CombatNaNGuard.ReportLevel hitViaHelperB(final Object key) {
        return CombatNaNGuard.hit(CombatNaNGuard.SITE_FLUX, key, "sampleB");
    }

    private static CombatNaNGuard.StatsSnapshot only(final Map<String, CombatNaNGuard.StatsSnapshot> stats) {
        assertEquals(1, stats.size());
        return stats.values().iterator().next();
    }
}
