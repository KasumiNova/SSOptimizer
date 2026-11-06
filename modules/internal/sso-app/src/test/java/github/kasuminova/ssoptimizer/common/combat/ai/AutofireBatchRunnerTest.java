package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertFalse(AutofireBatchRunner.isPluginClassWhitelisted(
                "com.genir.aitweaks.core.shipai.autofire.AutofireAI$Sub"));
        assertFalse(AutofireBatchRunner.isPluginClassWhitelisted(""));
    }
}
