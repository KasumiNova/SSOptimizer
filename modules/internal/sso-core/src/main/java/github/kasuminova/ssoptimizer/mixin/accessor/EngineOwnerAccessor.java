package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(targets = GameClassNames.ENGINE_OWNER_DOTTED)
public interface EngineOwnerAccessor {
    @Invoker(value = "getEngineLocations", remap = false)
    List<EngineSlot> ssoptimizer$getEngineLocations();

    @Invoker(value = "getAngularVelocity", remap = false)
    float ssoptimizer$getAngularVelocity();

    @Invoker(value = "getFacing", remap = false)
    float ssoptimizer$getFacing();

    @Invoker(value = "isMissile", remap = false)
    boolean ssoptimizer$isMissile();

    @Invoker(value = "isFighter", remap = false)
    boolean ssoptimizer$isFighter();
}