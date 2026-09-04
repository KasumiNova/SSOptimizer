package github.kasuminova.ssoptimizer.mixin.save;

import com.fs.starfarer.api.ModPlugin;
import github.kasuminova.ssoptimizer.common.save.MemoryRefResolveGuard;
import github.kasuminova.ssoptimizer.common.save.ModPluginLoadTimer;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 战役存档管理器读档 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.save.CampaignGameManager}<br>
 * 注入动机与效果分两部分：<br>
 * 1. 读档窗口守卫：读档方法 HEAD 进入 Memory 引用解析不安全窗口（
 * {@link MemoryRefResolveGuard}），阶段25 新引擎安装点关闭窗口并重放挂起解析，
 * 方法返回时清理状态；防止 unmarshal 期间模组 readResolve 链触碰舰队 memory
 * 导致 mRef_/enRef_ 键被原版静默删除。<br>
 * 2. 计时探针：重定向 {@code ModPlugin.onGameLoad} 调用点，外包计时并聚合到
 * {@link ModPluginLoadTimer}；读档返回时输出 TOP N 汇总。仅在
 * {@code ssoptimizer.save.modloadtiming=true} 时生效，默认零开销直通。
 */
@Mixin(targets = GameMixinSignatures.CampaignGameManager.TARGET_CLASS)
public abstract class CampaignGameManagerMixin {
    /**
     * 读档入口：进入 Memory 引用解析不安全窗口。
     *
     * @param cir 回调
     * @author KasumiNova
     * @reason 窗口起点必须覆盖整个 unmarshal 阶段，见 {@link MemoryRefResolveGuard}。
     */
    @Inject(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At("HEAD"), remap = false)
    private static void ssoptimizer$enterMemoryRefGuardWindow(final CallbackInfoReturnable<String> cir) {
        MemoryRefResolveGuard.enterLoad();
    }

    /**
     * 引擎安装点：转发安装事件给守卫，由其区分成功路径（装新引擎→关闭窗口并重放）
     * 与异常路径（恢复旧引擎→忽略）。
     *
     * @param engine 安装的引擎实例
     * @author KasumiNova
     * @reason 窗口终点以「引擎单例切换到新实例」为信号；方法内有两处 setInstance
     * 调用（成功与异常恢复），均须转发给守卫判断。
     */
    @Redirect(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignGameManager.ENGINE_SET_INSTANCE),
            expect = 2, remap = false)
    private static void ssoptimizer$notifyEngineInstalled(final com.fs.starfarer.campaign.CampaignEngine engine) {
        com.fs.starfarer.campaign.CampaignEngine.setInstance(engine);
        MemoryRefResolveGuard.onEngineInstalled(engine);
    }

    /**
     * 读档返回：清理守卫窗口状态（覆盖异常路径）。
     *
     * @param cir 回调
     * @author KasumiNova
     * @reason 失败路径不会走到新引擎安装点，窗口必须在方法返回时强制复位，
     * 否则窗口标志泄漏影响后续正常游玩的删键语义。
     */
    @Inject(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At("RETURN"), remap = false)
    private static void ssoptimizer$finishMemoryRefGuardWindow(final CallbackInfoReturnable<String> cir) {
        MemoryRefResolveGuard.loadFinished();
    }

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
