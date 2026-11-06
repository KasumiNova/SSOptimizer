package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.campaign.Faction;
import com.fs.starfarer.loading.FactionSpec;
import com.fs.starfarer.loading.SpecStore;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/** player spec 注册完成前创建的阵营快照必须在读取时重建。 */
@Mixin(targets = GameMixinSignatures.FactionManager.TARGET_CLASS)
public abstract class FactionManagerEarlyInitMixin {
    private static final Logger LOGGER = Logger.getLogger(FactionManagerEarlyInitMixin.class);

    @Shadow
    private Map<String, Faction> factions;

    @Shadow
    private Faction playerFaction;

    @Shadow
    protected abstract Object readResolve();

    @Inject(method = GameMixinSignatures.FactionManager.GET_PLAYER_FACTION, at = @At("HEAD"), remap = false)
    private void ssoptimizer$healOnGetPlayerFaction(final CallbackInfoReturnable<Faction> cir) {
        ssoptimizer$ensureFactionsSnapshotted();
    }

    @Inject(method = GameMixinSignatures.FactionManager.GET_FACTION, at = @At("HEAD"), remap = false)
    private void ssoptimizer$healOnGetFaction(final CallbackInfoReturnable<Faction> cir) {
        ssoptimizer$ensureFactionsSnapshotted();
    }

    @Inject(method = GameMixinSignatures.FactionManager.GET_ALL_FACTIONS, at = @At("HEAD"), remap = false)
    private void ssoptimizer$healOnGetAllFactions(final CallbackInfoReturnable<List<?>> cir) {
        ssoptimizer$ensureFactionsSnapshotted();
    }

    @Unique
    private synchronized void ssoptimizer$ensureFactionsSnapshotted() {
        if (playerFaction != null || !SpecStore.hasSpec(FactionSpec.class, "player")) {
            return;
        }
        readResolve();
        playerFaction = factions.get("player");
        if (playerFaction != null) {
            LOGGER.warn("[SSOptimizer] FactionManager 构造早于 faction spec 注册，已重新快照：factions="
                    + factions.size() + "（CampaignEngine 被提前创建，请检查脚本线程上的 mod 静态初始化）");
        }
    }
}
