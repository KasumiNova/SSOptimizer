package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.loading.specs.ShipHullSpec;
import com.fs.starfarer.loading.specs.WeaponSlot;
import github.kasuminova.ssoptimizer.common.render.campaign.WeaponSlotIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShipHullSpec.class)
public abstract class ShipHullSpecWeaponSlotMixin {
    @Unique
    private transient volatile WeaponSlotIndex ssoptimizer$slotIndex;

    @Inject(method = "getWeaponSlot", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$getWeaponSlotIndexed(final String id,
                                                  final CallbackInfoReturnable<WeaponSlot> cir) {
        WeaponSlotIndex index = ssoptimizer$slotIndex;
        if (index == null) {
            index = new WeaponSlotIndex();
            ssoptimizer$slotIndex = index;
        }
        cir.setReturnValue(index.find(((ShipHullSpec) (Object) this).getAllWeaponSlots(), id));
    }
}
