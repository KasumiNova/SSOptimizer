package github.kasuminova.ssoptimizer.render.engine;

import com.fs.graphics.Sprite;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.combat.entities.G;
import com.fs.starfarer.combat.entities.ship.H;
import com.fs.starfarer.loading.specs.EngineSlot;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;

public interface GEngineBridge {
    H.Oo ssoptimizer$getOwner();

    boolean ssoptimizer$isSystemActivatedRenderingEnabled();

    ColorShifter ssoptimizer$getColorShifter();

    ValueShifter ssoptimizer$getLengthShifter();

    ValueShifter ssoptimizer$getWidthShifter();

    ValueShifter ssoptimizer$getGlowShifter();

    float ssoptimizer$getColorShiftFraction();

    boolean ssoptimizer$isBoostedFlameMode();

    Fader ssoptimizer$getPrimaryFader();

    Fader ssoptimizer$getSecondaryFader();

    com.fs.graphics.Object ssoptimizer$getPrimaryGlowTexture();

    com.fs.graphics.Object ssoptimizer$getSecondaryGlowTexture();

    com.fs.graphics.Object ssoptimizer$getFlameTexture();

    Sprite ssoptimizer$getGlowSprite();

    G.Oo ssoptimizer$getState(EngineSlot slot);

    void ssoptimizer$renderFighter(float alphaScale);
}