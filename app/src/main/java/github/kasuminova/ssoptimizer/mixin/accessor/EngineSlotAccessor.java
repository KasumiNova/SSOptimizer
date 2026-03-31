package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.entities.G;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.awt.*;

@Mixin(targets = "com.fs.starfarer.loading.specs.EngineSlot")
public interface EngineSlotAccessor {
    @Invoker(value = "isOmegaMode", remap = false)
    boolean ssoptimizer$isOmegaMode();

    @Invoker(value = "isWithSpread", remap = false)
    boolean ssoptimizer$isWithSpread();

    @Invoker(value = "isSystemActivated", remap = false)
    boolean ssoptimizer$isSystemActivated();

    @Invoker(value = "computeMidArcAngle", remap = false)
    float ssoptimizer$computeMidArcAngle(float facing);

    @Invoker(value = "computePosition", remap = false)
    Vector2f ssoptimizer$computePosition(Vector2f destination, float facing);

    @Invoker(value = "getMaxSpread", remap = false)
    float ssoptimizer$getMaxSpread();

    @Invoker(value = "getColor", remap = false)
    Color ssoptimizer$getColor();

    @Invoker(value = "getLength", remap = false)
    float ssoptimizer$getLength();

    @Invoker(value = "getWidth", remap = false)
    float ssoptimizer$getWidth();

    @Invoker(value = "getGlowType", remap = false)
    G.o ssoptimizer$getGlowType();

    @Invoker(value = "getGlowAlternateColor", remap = false)
    Color ssoptimizer$getGlowAlternateColor();

    @Invoker(value = "getGlowSizeMult", remap = false)
    float ssoptimizer$getGlowSizeMult();
}