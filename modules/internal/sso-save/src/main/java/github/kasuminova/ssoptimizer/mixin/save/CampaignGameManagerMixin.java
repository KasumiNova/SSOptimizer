package github.kasuminova.ssoptimizer.mixin.save;

import com.fs.starfarer.api.ModPlugin;
import github.kasuminova.ssoptimizer.common.save.MemoryRefResolveGuard;
import github.kasuminova.ssoptimizer.common.save.ModPluginLoadTimer;
import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameMixinSignatures.CampaignGameManager.TARGET_CLASS)
public abstract class CampaignGameManagerMixin {
    @Inject(method = {GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            "saveGame(Lcom/fs/starfarer/campaign/CampaignEngine$CampaignUI;JZ)Ljava/lang/String;"},
            at = @At("RETURN"), remap = false)
    private static void ssoptimizer$releaseProgressDialog(final CallbackInfoReturnable<String> cir) {
        SaveProgressOverlayCoordinator.releaseStreamDialog();
    }

    @Inject(method = "saveGame(Lcom/fs/starfarer/campaign/CampaignEngine$CampaignUI;JZ)Ljava/lang/String;",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/campaign/save/CampaignGameManager;setLastSaveTime(J)V",
                    shift = At.Shift.AFTER), remap = false)
    private static void ssoptimizer$presentCompletedSave(final CallbackInfoReturnable<String> cir) {
        SaveProgressOverlayCoordinator.complete();
        SaveProgressOverlayCoordinator.maybePumpFrame();
    }

    @Inject(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At("HEAD"), remap = false)
    private static void ssoptimizer$enterMemoryRefGuardWindow(final CallbackInfoReturnable<String> cir) {
        MemoryRefResolveGuard.enterLoad();
    }

    @Redirect(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignGameManager.ENGINE_SET_INSTANCE),
            expect = 2, remap = false)
    private static void ssoptimizer$notifyEngineInstalled(final com.fs.starfarer.campaign.CampaignEngine engine) {
        com.fs.starfarer.campaign.CampaignEngine.setInstance(engine);
        MemoryRefResolveGuard.onEngineInstalled(engine);
    }

    @Inject(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At("RETURN"), remap = false)
    private static void ssoptimizer$finishMemoryRefGuardWindow(final CallbackInfoReturnable<String> cir) {
        MemoryRefResolveGuard.loadFinished();
    }

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

    @Inject(method = GameMixinSignatures.CampaignGameManager.LOAD_GAME,
            at = @At("RETURN"), remap = false)
    private static void ssoptimizer$dumpModPluginTimings(final CallbackInfoReturnable<String> cir) {
        if (ModPluginLoadTimer.isEnabled()) {
            ModPluginLoadTimer.dumpAndReset();
        }
    }
}
