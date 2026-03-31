package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.Sprite;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.combat.entities.G;
import com.fs.starfarer.combat.entities.ship.H;
import com.fs.starfarer.loading.specs.EngineSlot;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import github.kasuminova.ssoptimizer.render.engine.GEngineBridge;
import github.kasuminova.ssoptimizer.render.engine.GRenderHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "com.fs.starfarer.combat.entities.G")
public abstract class GEngineRenderMixin implements GEngineBridge {
    @Shadow(remap = false, aliases = "Óo0000")
    private H.Oo ssoptimizer$owner;

    @Shadow(remap = false, aliases = "õ00000")
    private boolean ssoptimizer$systemActivatedRenderingEnabled;

    @Shadow(remap = false, aliases = "öO0000")
    private ColorShifter ssoptimizer$colorShifter;

    @Shadow(remap = false, aliases = "class")
    private ValueShifter ssoptimizer$lengthShifter;

    @Shadow(remap = false, aliases = "Oo0000")
    private ValueShifter ssoptimizer$widthShifter;

    @Shadow(remap = false, aliases = "Øo0000")
    private ValueShifter ssoptimizer$glowShifter;

    @Shadow(remap = false, aliases = "interface")
    private float ssoptimizer$colorShiftFraction;

    @Shadow(remap = false, aliases = "ØO0000")
    private boolean ssoptimizer$boostedFlameMode;

    @Shadow(remap = false, aliases = "null")
    private Fader ssoptimizer$primaryFader;

    @Shadow(remap = false, aliases = "new")
    private Fader ssoptimizer$secondaryFader;

    @Shadow(remap = false, aliases = "ÓO0000")
    private com.fs.graphics.Object ssoptimizer$primaryGlowTexture;

    @Shadow(remap = false, aliases = "ÔO0000")
    private com.fs.graphics.Object ssoptimizer$secondaryGlowTexture;

    @Shadow(remap = false, aliases = "class.super")
    private com.fs.graphics.Object ssoptimizer$flameTexture;

    @Shadow(remap = false, aliases = "oo0000")
    private Sprite ssoptimizer$glowSprite;

    @Invoker(value = "Object", remap = false)
    protected abstract G.Oo ssoptimizer$invokeGetState(EngineSlot slot);

    @Invoker(value = "Õ00000", remap = false)
    protected abstract void ssoptimizer$invokeRenderFighter(float alphaScale);

    @Override
    public H.Oo ssoptimizer$getOwner() {
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
    public com.fs.graphics.Object ssoptimizer$getPrimaryGlowTexture() {
        return ssoptimizer$primaryGlowTexture;
    }

    @Override
    public com.fs.graphics.Object ssoptimizer$getSecondaryGlowTexture() {
        return ssoptimizer$secondaryGlowTexture;
    }

    @Override
    public com.fs.graphics.Object ssoptimizer$getFlameTexture() {
        return ssoptimizer$flameTexture;
    }

    @Override
    public Sprite ssoptimizer$getGlowSprite() {
        return ssoptimizer$glowSprite;
    }

    @Override
    public G.Oo ssoptimizer$getState(EngineSlot slot) {
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
    public void o00000(float alphaScale) {
        GRenderHelper.renderEngines(this, alphaScale);
    }
}