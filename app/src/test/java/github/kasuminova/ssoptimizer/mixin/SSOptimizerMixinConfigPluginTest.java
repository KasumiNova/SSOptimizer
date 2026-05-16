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
    }

    @Test
    void skipsEngineRenderMixinByDefault() {
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "com.fs.starfarer.combat.entities.Engine",
                "github.kasuminova.ssoptimizer.mixin.render.EngineRenderMixin"));
    }

    @Test
    void appliesEngineRenderMixinWhenExplicitlyEnabled() {
        System.setProperty(ShipEngineRenderOptimizationToggle.ENABLE_PROPERTY, "true");
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertTrue(plugin.shouldApplyMixin(
                "com.fs.starfarer.combat.entities.Engine",
                "github.kasuminova.ssoptimizer.mixin.render.EngineRenderMixin"));
    }

    @Test
    void keepsOtherMixinsEnabledByDefault() {
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertTrue(plugin.shouldApplyMixin(
                "com.fs.starfarer.loading.ResourceLoader",
                "github.kasuminova.ssoptimizer.mixin.loading.SoundManagerMixin"));
    }
}