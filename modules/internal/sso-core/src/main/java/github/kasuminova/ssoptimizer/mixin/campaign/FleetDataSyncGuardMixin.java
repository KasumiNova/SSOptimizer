package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.campaign.fleet.FleetData;
import github.kasuminova.ssoptimizer.common.campaign.FleetDataSyncGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

/** 注入体不得引用 mixin 包内辅助类，因此守卫逻辑放在 {@link FleetDataSyncGuard}。 */
@Mixin(targets = GameClassNames.FLEET_DATA_DOTTED, remap = false)
public abstract class FleetDataSyncGuardMixin {
    @Shadow
    private boolean forceNoSync;

    @Inject(method = "syncIfNeeded", at = @At("HEAD"))
    private void ssoptimizer$detectStuckSync(final CallbackInfo ci) {
        FleetDataSyncGuard.detectStuckSync((FleetData) (Object) this, this.forceNoSync);
    }

    @Redirect(method = "syncIfNeeded", remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lcom/fs/starfarer/campaign/fleet/FleetData;forceNoSync:Z"))
    private void ssoptimizer$guardForceNoSyncWrite(final FleetData self, final boolean value) {
        FleetDataSyncGuard.recordForceNoSyncWrite(self, value);
        self.setForceNoSync(value);
    }
}
