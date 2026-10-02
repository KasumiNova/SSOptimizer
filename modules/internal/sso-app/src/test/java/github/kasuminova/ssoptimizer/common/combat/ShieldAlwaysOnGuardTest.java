package github.kasuminova.ssoptimizer.common.combat;

import com.fs.starfarer.api.combat.ShieldAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShieldAlwaysOnGuard} 核心判定与报告节流逻辑验证。
 * <p>
 * 游戏实体无法脱离引擎实例化，注入锚点（advanceInCombat 内唯一 isOn() 调用点）
 * 由 ShieldAlwaysOnNullShieldMixinAnchorTest 以字节码核验覆盖；
 * 本测试直接调用逻辑入口验证：有盾原样委托、无盾按「已开启」放行、
 * 无盾报告按舰船实例/未知身份各仅输出一次。
 */
class ShieldAlwaysOnGuardTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ShieldAlwaysOnGuard.resetForTesting();
    }

    @Test
    void delegatesToShieldWhenPresent() {
        StubShield on = new StubShield(true);
        StubShield off = new StubShield(false);
        assertTrue(ShieldAlwaysOnGuard.isShieldOnOrAbsent(on, null), "有盾且开启必须原样返回 true");
        assertFalse(ShieldAlwaysOnGuard.isShieldOnOrAbsent(off, null), "有盾且关闭必须原样返回 false");
        assertTrue(on.isOnCalls == 1 && off.isOnCalls == 1, "有盾路径必须恰好委托一次 isOn()");
    }

    @Test
    void absentShieldTreatedAsOn() {
        assertTrue(ShieldAlwaysOnGuard.isShieldOnOrAbsent(null, null),
                "无盾必须视为「已开启」以跳过 toggleOn");
    }

    @Test
    void unknownIdentityReportsOnlyOnce() {
        assertTrue(ShieldAlwaysOnGuard.reportNullShieldOnce(null), "首次未知身份命中必须报告");
        assertFalse(ShieldAlwaysOnGuard.reportNullShieldOnce(null), "后续未知身份命中必须被节流");
    }

    @Test
    void reportThrottleResetWorks() {
        assertTrue(ShieldAlwaysOnGuard.reportNullShieldOnce(null));
        ShieldAlwaysOnGuard.resetForTesting();
        assertTrue(ShieldAlwaysOnGuard.reportNullShieldOnce(null), "重置后必须允许再次报告");
    }

    /** 最小 ShieldAPI 桩：仅 isOn() 携带状态与调用计数，其余方法不参与本守卫逻辑。 */
    private static final class StubShield implements ShieldAPI {
        private final boolean on;
        private int isOnCalls;

        private StubShield(final boolean on) {
            this.on = on;
        }

        @Override
        public boolean isOn() {
            isOnCalls++;
            return on;
        }

        @Override
        public void setType(final ShieldType type) { }

        @Override
        public ShieldType getType() {
            return ShieldType.NONE;
        }

        @Override
        public float getFacing() {
            return 0;
        }

        @Override
        public float getArc() {
            return 0;
        }

        @Override
        public float getActiveArc() {
            return 0;
        }

        @Override
        public void setActiveArc(final float arc) { }

        @Override
        public float getRadius() {
            return 0;
        }

        @Override
        public boolean isOff() {
            return !on;
        }

        @Override
        public Vector2f getLocation() {
            return null;
        }

        @Override
        public boolean isWithinArc(final Vector2f point) {
            return false;
        }

        @Override
        public void toggleOff() { }

        @Override
        public float getFluxPerPointOfDamage() {
            return 0;
        }

        @Override
        public void setArc(final float arc) { }

        @Override
        public void setInnerColor(final Color color) { }

        @Override
        public void setRingColor(final Color color) { }

        @Override
        public Color getInnerColor() {
            return null;
        }

        @Override
        public Color getRingColor() {
            return null;
        }

        @Override
        public float getUpkeep() {
            return 0;
        }

        @Override
        public void forceFacing(final float facing) { }

        @Override
        public void setRadius(final float radius) { }

        @Override
        public void setRadius(final float radius, final String spriteId, final String explosionId) { }

        @Override
        public void toggleOn() { }

        @Override
        public float getUnfoldTime() {
            return 0;
        }

        @Override
        public void setCenter(final float x, final float y) { }

        @Override
        public float getInnerRotationRate() {
            return 0;
        }

        @Override
        public void setInnerRotationRate(final float rate) { }

        @Override
        public float getRingRotationRate() {
            return 0;
        }

        @Override
        public void setRingRotationRate(final float rate) { }

        @Override
        public boolean isSkipRendering() {
            return false;
        }

        @Override
        public void setSkipRendering(final boolean skip) { }

        @Override
        public void applyShieldEffects(final Color inner, final Color ring,
                                       final float brightness, final float innerAlpha,
                                       final float ringAlpha) { }
    }
}
