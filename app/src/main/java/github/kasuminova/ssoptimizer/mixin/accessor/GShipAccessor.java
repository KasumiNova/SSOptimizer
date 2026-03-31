package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.systems.F;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "com.fs.starfarer.combat.entities.Ship")
public interface GShipAccessor {
    @Invoker(value = "getSystem", remap = false)
    F ssoptimizer$getSystem();
}