package github.kasuminova.ssoptimizer.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.fs.starfarer.combat.entities.G$Oo")
public interface GEngineStateAccessor {
    @Accessor(value = "Ø00000", remap = false)
    float ssoptimizer$getTexU();

    @Accessor(value = "Ò00000", remap = false)
    float ssoptimizer$getCoreRotation();

    @Accessor(value = "return", remap = false)
    float ssoptimizer$getSpread();

    @Accessor(value = "Õ00000", remap = false)
    float ssoptimizer$getFlameLevel();
}