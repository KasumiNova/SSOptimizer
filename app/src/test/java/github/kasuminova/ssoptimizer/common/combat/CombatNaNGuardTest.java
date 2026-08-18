package github.kasuminova.ssoptimizer.common.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CombatNaNGuard} 核心判定与日志节流逻辑验证。
 * <p>
 * 游戏实体（CollisionEntity/Damage）无法脱离引擎实例化，实体级方法由
 * NaNGuardMixinInjectionTest 以字节码核验覆盖注入正确性；本测试直接调用
 * 纯逻辑入口验证数值判定与「键 × 检查点」节流语义。
 */
class CombatNaNGuardTest {

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
    void shouldReportFiresOncePerKeyAndSite() {
        Object key = new Object();
        assertTrue(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_MOTION), "同键同检查点首次必须报告");
        assertFalse(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_MOTION), "同键同检查点第二次必须抑制");
        assertTrue(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_DAMAGE), "同键不同检查点必须独立报告");
        assertFalse(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_DAMAGE), "位掩码叠加后仍须抑制");
    }

    @Test
    void shouldReportIsolatesDifferentKeys() {
        Object a = new Object();
        Object b = new Object();
        assertTrue(CombatNaNGuard.shouldReport(a, CombatNaNGuard.SITE_FLUX));
        assertTrue(CombatNaNGuard.shouldReport(b, CombatNaNGuard.SITE_FLUX), "不同键互不影响");
        assertFalse(CombatNaNGuard.shouldReport(a, CombatNaNGuard.SITE_FLUX));
        assertFalse(CombatNaNGuard.shouldReport(b, CombatNaNGuard.SITE_FLUX));
    }

    @Test
    void shouldReportAccumulatesSiteBits() {
        Object key = new Object();
        assertTrue(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_MOTION));
        assertTrue(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_HITPOINTS));
        assertTrue(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_COLLISION));
        assertFalse(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_MOTION));
        assertFalse(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_HITPOINTS));
        assertFalse(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_COLLISION));
        assertTrue(CombatNaNGuard.shouldReport(key, CombatNaNGuard.SITE_FLUX), "未触及的检查点仍可报告");
    }
}
