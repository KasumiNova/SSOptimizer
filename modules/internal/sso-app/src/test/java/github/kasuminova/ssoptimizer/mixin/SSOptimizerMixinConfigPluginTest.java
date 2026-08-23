package github.kasuminova.ssoptimizer.mixin;

import github.kasuminova.ssoptimizer.common.render.ShipEngineRenderOptimizationToggle;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SSOptimizerMixinConfigPluginTest {
    @AfterEach
    void clearEngineRenderProperty() {
        System.clearProperty(ShipEngineRenderOptimizationToggle.ENABLE_PROPERTY);
        System.clearProperty(SSOptimizerMixinConfigPlugin.AI_PARALLEL_DISABLE_PROPERTY);
        System.clearProperty(RenderThreadMode.ENABLE_PROPERTY);
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

    @Test
    void appliesBridgeDependentMixinsWhenRenderThreadEnabled() {
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertTrue(plugin.shouldApplyMixin(
                "com.fs.graphics.Sprite",
                "github.kasuminova.ssoptimizer.mixin.render.SpriteMixin"));
        assertTrue(plugin.shouldApplyMixin(
                "com.fs.graphics.font.BitmapFontRenderer",
                "github.kasuminova.ssoptimizer.mixin.render.BitmapFontRendererMixin"));
    }

    @Test
    void skipsBridgeDependentMixinsWhenRenderThreadDisabled() {
        System.setProperty(RenderThreadMode.ENABLE_PROPERTY, "false");
        SSOptimizerMixinConfigPlugin plugin = new SSOptimizerMixinConfigPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "com.fs.graphics.Sprite",
                "github.kasuminova.ssoptimizer.mixin.render.SpriteMixin"),
                "RT 关闭时 bridge 未安装，SpriteMixin 的 bridge 流式调用必崩，必须禁用");
        assertFalse(plugin.shouldApplyMixin(
                "com.fs.graphics.font.BitmapFontRenderer",
                "github.kasuminova.ssoptimizer.mixin.render.BitmapFontRendererMixin"),
                "RT 关闭时 v2 文本管线（动态图集 GlDispatch 上传）不可用，必须禁用回退原版");
        // 其余 Mixin 不受 RT 开关影响
        assertTrue(plugin.shouldApplyMixin(
                "com.fs.starfarer.loading.ResourceLoader",
                "github.kasuminova.ssoptimizer.mixin.loading.SoundManagerMixin"));
    }
}