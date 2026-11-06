package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.Sprite;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.combat.entities.Engine.EngineState;
import com.fs.starfarer.combat.entities.ship.ShipEngineController.EngineOwner;
import com.fs.starfarer.loading.specs.EngineSlot;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;

public interface EngineBridge {
    EngineOwner ssoptimizer$getOwner();

    boolean ssoptimizer$isSystemActivatedRenderingEnabled();

    ColorShifter ssoptimizer$getColorShifter();

    ValueShifter ssoptimizer$getLengthShifter();

    ValueShifter ssoptimizer$getWidthShifter();

    ValueShifter ssoptimizer$getGlowShifter();

    float ssoptimizer$getColorShiftFraction();

    boolean ssoptimizer$isBoostedFlameMode();

    Fader ssoptimizer$getPrimaryFader();

    Fader ssoptimizer$getSecondaryFader();

    com.fs.graphics.TextureObject ssoptimizer$getPrimaryGlowTexture();

    com.fs.graphics.TextureObject ssoptimizer$getSecondaryGlowTexture();

    com.fs.graphics.TextureObject ssoptimizer$getFlameTexture();

    Sprite ssoptimizer$getGlowSprite();

    EngineState ssoptimizer$getState(EngineSlot slot);

    void ssoptimizer$renderFighter(float alphaScale);
}