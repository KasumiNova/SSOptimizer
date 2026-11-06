package github.kasuminova.ssoptimizer.mixin.accessor;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = GameClassNames.ENGINE_STATE_DOTTED)
public interface EngineStateAccessor {
    @Accessor(value = "texU", remap = false)
    float ssoptimizer$getTexU();

    @Accessor(value = "coreRotation", remap = false)
    float ssoptimizer$getCoreRotation();

    @Accessor(value = "spread", remap = false)
    float ssoptimizer$getSpread();

    @Accessor(value = "flameLevel", remap = false)
    float ssoptimizer$getFlameLevel();
}