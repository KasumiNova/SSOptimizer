package github.kasuminova.ssoptimizer.mixin;

import github.kasuminova.ssoptimizer.common.render.ShipEngineRenderOptimizationToggle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SSOptimizerMixinConfigPluginTest {
    @AfterEach
    void clearEngineRenderProperty() {
        System.clearProperty(ShipEngineRenderOptimizationToggle.ENABLE_PROPERTY);
        System.clearProperty(SSOptimizerMixinConfigPlugin.AI_PARALLEL_DISABLE_PROPERTY);
    }

    @Test
    void appliesEngineRenderMixinByDefault() {
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertTrue(plugin.shouldApplyMixin(
                "com.fs.starfarer.combat.entities.Engine",
                "github.kasuminova.ssoptimizer.mixin.render.EngineRenderMixin"));
    }

    @Test
    void skipsEngineRenderMixinWhenExplicitlyDisabled() {
        System.setProperty(ShipEngineRenderOptimizationToggle.ENABLE_PROPERTY, "false");
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "com.fs.starfarer.combat.entities.Engine",
                "github.kasuminova.ssoptimizer.mixin.render.EngineRenderMixin"));
    }

    @Test
    void skipsAiMixinsWhenParallelAiDisabled() {
        System.setProperty(SSOptimizerMixinConfigPlugin.AI_PARALLEL_DISABLE_PROPERTY, "true");
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "com.fs.starfarer.combat.CombatEngine",
                "github.kasuminova.ssoptimizer.mixin.ai.CombatEngineAiParallelMixin"));
    }

    @Test
    void appliesAiMixinsByDefault() {
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertTrue(plugin.shouldApplyMixin(
                "com.fs.starfarer.combat.CombatEngine",
                "github.kasuminova.ssoptimizer.mixin.ai.CombatEngineAiParallelMixin"));
    }

    @Test
    void keepsOtherMixinsEnabledByDefault() {
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertTrue(plugin.shouldApplyMixin(
                "com.fs.starfarer.loading.ResourceLoader",
                "github.kasuminova.ssoptimizer.mixin.loading.SoundManagerMixin"));
    }
}