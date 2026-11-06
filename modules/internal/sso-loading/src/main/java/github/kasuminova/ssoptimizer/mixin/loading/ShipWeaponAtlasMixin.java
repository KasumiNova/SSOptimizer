package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(targets = GameClassNames.RESOURCE_LOADER_STATE_DOTTED)
public abstract class ShipWeaponAtlasMixin {

    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void ssoptimizer$buildShipWeaponAtlas(final Map map, final CallbackInfo ci) {
        ShipWeaponAtlas.build();
    }
}
