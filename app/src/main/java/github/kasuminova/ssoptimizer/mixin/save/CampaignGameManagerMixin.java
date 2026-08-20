package github.kasuminova.ssoptimizer.mixin.save;

import com.fs.starfarer.api.ModPlugin;
import github.kasuminova.ssoptimizer.common.save.ModPluginLoadTimer;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 战役存档管理器读档后处理的计时探针 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.save.CampaignGameManager}<br>
 * 注入动机：读档基准显示「载入阶段33→34」（模组 {@code onGameLoad} 循环 + 经济重算）约占全程 40%，
 * 需要逐项计时数据定位具体耗时模组，供 modopt 针对性优化。<br>
 * 注入效果：重定向 {@code ModPlugin.onGameLoad} 调用点，外包计时并聚合到
 * {@link ModPluginLoadTimer}；读档返回时输出 TOP N 汇总。仅在
 * {@code ssoptimizer.save.modloadtiming=true} 时生效，默认零开销直通。
 */
@Mixin(targets = GameMixinSignatures.CampaignGameManager.TARGET_CLASS)
public abstract class CampaignGameManagerMixin {
    /**
     * 带计时的模组 onGameLoad 调用。
     *
     * @param plugin  模组插件实例
     * @param newGame 是否新游戏（读档路径恒为 false）
     * @author KasumiNova
     * @reason 为读档后处理的模组耗时分布提供逐项数据；未启用时直通原调用。
     */
    @Redirect(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignGameManager.MOD_PLUGIN_ON_GAME_LOAD),
            remap = false)
    private static void ssoptimizer$timedOnGameLoad(final ModPlugin plugin, final boolean newGame) {
        if (!ModPluginLoadTimer.isEnabled()) {
            plugin.onGameLoad(newGame);
            return;
        }
        final long start = System.nanoTime();
        try {
            plugin.onGameLoad(newGame);
        } finally {
            ModPluginLoadTimer.record(plugin.getClass(), System.nanoTime() - start);
        }
    }

    /**
     * 读档返回时输出计时汇总。
     *
     * @param cir 回调（携带错误消息或 null）
     * @author KasumiNova
     * @reason 每轮读档结束输出一次模组耗时分布并重置，供下一轮统计。
     */
    @Inject(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At("RETURN"), remap = false)
    private static void ssoptimizer$dumpModPluginTimings(final CallbackInfoReturnable<String> cir) {
        if (ModPluginLoadTimer.isEnabled()) {
            ModPluginLoadTimer.dumpAndReset();
        }
    }
}
