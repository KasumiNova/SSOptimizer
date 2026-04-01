package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.Sprite;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.combat.entities.EngineState;
import com.fs.starfarer.combat.entities.ship.EngineOwner;
import com.fs.starfarer.loading.specs.EngineSlot;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import github.kasuminova.ssoptimizer.common.render.engine.EngineBridge;
import github.kasuminova.ssoptimizer.common.render.engine.EngineRenderHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 引擎实体（Engine）的渲染 Mixin，完整替换原始 {@code render()} 方法。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.Engine}<br>
 * 注入动机：原始引擎渲染逻辑为逐实体独立绘制，存在大量重复 OpenGL 状态切换和纹理绑定；
 * 通过 {@code @Overwrite} 替换为批量化渲染路径 ({@link EngineRenderHelper})，显著减少 draw call。<br>
 * 注入效果：实现 {@link EngineBridge} 接口暴露 Shadow 字段，{@code render()} 委托给
 * {@link EngineRenderHelper} 进行批量渲染。</p>
 */
@Mixin(targets = GameClassNames.ENGINE_DOTTED)
public abstract class EngineRenderMixin implements EngineBridge {
    @Shadow(remap = false, aliases = "owner")
    private EngineOwner ssoptimizer$owner;

    @Shadow(remap = false, aliases = "systemActivatedRenderingEnabled")
    private boolean ssoptimizer$systemActivatedRenderingEnabled;

    @Shadow(remap = false, aliases = "colorShifter")
    private ColorShifter ssoptimizer$colorShifter;

    @Shadow(remap = false, aliases = "lengthShifter")
    private ValueShifter ssoptimizer$lengthShifter;

    @Shadow(remap = false, aliases = "widthShifter")
    private ValueShifter ssoptimizer$widthShifter;

    @Shadow(remap = false, aliases = "glowShifter")
    private ValueShifter ssoptimizer$glowShifter;

    @Shadow(remap = false, aliases = "colorShiftFraction")
    private float ssoptimizer$colorShiftFraction;

    @Shadow(remap = false, aliases = "boostedFlameMode")
    private boolean ssoptimizer$boostedFlameMode;

    @Shadow(remap = false, aliases = "primaryFader")
    private Fader ssoptimizer$primaryFader;

    @Shadow(remap = false, aliases = "secondaryFader")
    private Fader ssoptimizer$secondaryFader;

    @Shadow(remap = false, aliases = "primaryGlowTexture")
    private com.fs.graphics.TextureObject ssoptimizer$primaryGlowTexture;

    @Shadow(remap = false, aliases = "secondaryGlowTexture")
    private com.fs.graphics.TextureObject ssoptimizer$secondaryGlowTexture;

    @Shadow(remap = false, aliases = "flameTexture")
    private com.fs.graphics.TextureObject ssoptimizer$flameTexture;

    @Shadow(remap = false, aliases = "glowSprite")
    private Sprite ssoptimizer$glowSprite;

    @Invoker(value = "getState", remap = false)
    protected abstract EngineState ssoptimizer$invokeGetState(EngineSlot slot);

    @Invoker(value = "renderFighter", remap = false)
    protected abstract void ssoptimizer$invokeRenderFighter(float alphaScale);

    @Override
    public EngineOwner ssoptimizer$getOwner() {
        return ssoptimizer$owner;
    }

    @Override
    public boolean ssoptimizer$isSystemActivatedRenderingEnabled() {
        return ssoptimizer$systemActivatedRenderingEnabled;
    }

    @Override
    public ColorShifter ssoptimizer$getColorShifter() {
        return ssoptimizer$colorShifter;
    }

    @Override
    public ValueShifter ssoptimizer$getLengthShifter() {
        return ssoptimizer$lengthShifter;
    }

    @Override
    public ValueShifter ssoptimizer$getWidthShifter() {
        return ssoptimizer$widthShifter;
    }

    @Override
    public ValueShifter ssoptimizer$getGlowShifter() {
        return ssoptimizer$glowShifter;
    }

    @Override
    public float ssoptimizer$getColorShiftFraction() {
        return ssoptimizer$colorShiftFraction;
    }

    @Override
    public boolean ssoptimizer$isBoostedFlameMode() {
        return ssoptimizer$boostedFlameMode;
    }

    @Override
    public Fader ssoptimizer$getPrimaryFader() {
        return ssoptimizer$primaryFader;
    }

    @Override
    public Fader ssoptimizer$getSecondaryFader() {
        return ssoptimizer$secondaryFader;
    }

    @Override
    public com.fs.graphics.TextureObject ssoptimizer$getPrimaryGlowTexture() {
        return ssoptimizer$primaryGlowTexture;
    }

    @Override
    public com.fs.graphics.TextureObject ssoptimizer$getSecondaryGlowTexture() {
        return ssoptimizer$secondaryGlowTexture;
    }

    @Override
    public com.fs.graphics.TextureObject ssoptimizer$getFlameTexture() {
        return ssoptimizer$flameTexture;
    }

    @Override
    public Sprite ssoptimizer$getGlowSprite() {
        return ssoptimizer$glowSprite;
    }

    @Override
    public EngineState ssoptimizer$getState(EngineSlot slot) {
        return ssoptimizer$invokeGetState(slot);
    }

    @Override
    public void ssoptimizer$renderFighter(float alphaScale) {
        ssoptimizer$invokeRenderFighter(alphaScale);
    }

    /**
     * @author GitHub Copilot
     * @reason Replace the hottest non-fighter engine-flame path with a helper
     * that collapses the immediate-mode GL storm into a small number of helper
     * calls while preserving the original visual math.
     */
    @Overwrite(remap = false)
    public void render(float alphaScale) {
        EngineRenderHelper.renderEngines(this, alphaScale);
    }
}