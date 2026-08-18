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

/**
 * 舰队数据同步卡死守卫的注入层。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.fleet.FleetData#syncIfNeeded()}<br>
 * 注入效果：HEAD 注入检测 forceNoSync 卡死；PUTFIELD 重定向记录 forceNoSync 写入点。
 * 全部逻辑委托 {@link FleetDataSyncGuard}——注入体会被内联进目标类，
 * mixin 包内的辅助类不允许被变换后的游戏类直接引用（IllegalClassLoadError），
 * 故守卫状态与判定逻辑必须放在 mixin 包之外。
 */
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
