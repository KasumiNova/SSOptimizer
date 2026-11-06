package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.systems.ShipSystemBase;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = GameClassNames.SHIP_DOTTED)
public interface ShipAccessor {
    @Invoker(value = "getSystem", remap = false)
    ShipSystemBase ssoptimizer$getSystem();
}