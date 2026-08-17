package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutofireBatchRunner#isPluginClassWhitelisted} 白名单契约验证。
 * 白名单类名是静默契约：类名写错不会报错，只会导致半并行化静默失效，必须显式锁定。
 */
class AutofireBatchRunnerTest {

    @Test
    void vanillaAndAiTweaksPluginsAreWhitelisted() {
        assertTrue(AutofireBatchRunner.isPluginClassWhitelisted(
                "com.fs.starfarer.combat.ai.PointDefenseAutofireAI"));
        assertTrue(AutofireBatchRunner.isPluginClassWhitelisted(
                "com.genir.aitweaks.core.shipai.autofire.AutofireAI"));
        assertTrue(AutofireBatchRunner.isPluginClassWhitelisted(
                "com.genir.aitweaks.core.shipai.autofire.RecklessAutofireAI"));
    }

    @Test
    void unknownPluginClassesAreRejected() {
        assertFalse(AutofireBatchRunner.isPluginClassWhitelisted(
                "com.example.mod.CustomWeaponAI"));
        // 子类/同名不同包不得命中：精确类名匹配
        assertFalse(AutofireBatchRunner.isPluginClassWhitelisted(
                "com.genir.aitweaks.core.shipai.autofire.AutofireAI$Sub"));
        assertFalse(AutofireBatchRunner.isPluginClassWhitelisted(""));
    }
}
