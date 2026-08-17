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

/**
 * 修复 {@code FactionManager} 被提前构造导致的 player faction 永久为空。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.FactionManager} 的
 * {@code getPlayerFaction()}/{@code getFaction(String)}/{@code getAllFactions()}<br>
 * 注入动机：{@code FactionManager} 构造器对 {@code SpecStore} 中的 {@code FactionSpec}
 * 做一次性快照并缓存 player faction；当脚本编译线程上的 mod 脚本类静态初始化
 * （如 SOTF 的 SOTFCEO_threatFabSwarm）在 faction spec 注册完成前触发
 * {@code CampaignEngine.getInstance()} 时，快照为空，player faction 永久为 {@code null}，
 * 标题界面 codex 初始化（{@code Misc.getBasePlayerColor()}）随即 NPE。<br>
 * 注入效果：读取路径上检测「player faction 为空但 player spec 已注册」的过期快照，
 * 重新执行 {@code readResolve()} 补齐全部阵营并重建 player faction 引用。
 * {@code readResolve()} 本身幂等（按 specId 去重），正常构造的实例不会触发自愈。
 */
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

    /**
     * 快照过期时重建阵营表。仅在 player faction 缺失且 player spec 已注册时触发，
     * 触发即说明构造早于 spec 注册；重建后记录警告日志以便追踪提前创建的调用方。
     */
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
