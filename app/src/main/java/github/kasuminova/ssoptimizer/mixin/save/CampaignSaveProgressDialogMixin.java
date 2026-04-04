package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 战役保存进度 UI 的线程解耦 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.save.CampaignSaveProgressDialog}<br>
 * 注入动机：原版会在该类的进度回调里直接执行 Sprite/UI 渲染与 {@code Display.update()}，
 * 一旦底层写出迁移到后台线程，就会在无 OpenGL 上下文的线程里崩溃。<br>
 * 注入效果：保留原版的状态文本与进度语义，但不再在后台线程的回调现场渲染，而是把状态转交给
 * {@link SaveProgressOverlayCoordinator}，再由主线程重放原版对话框方法。
 */
@Mixin(targets = GameMixinSignatures.CampaignSaveProgressDialog.TARGET_CLASS)
public abstract class CampaignSaveProgressDialogMixin {
    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.STRING_CONSTRUCTOR,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$captureSaveLabel(final String label,
                                              final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.attachSaveLabel((com.fs.starfarer.campaign.save.CampaignSaveProgressDialog) (Object) this, label);
    }

    /**
     * 发布带文本的保存进度。
     *
     * @param text     状态文本
     * @param progress 百分比进度值（0-100）
     * @author GitHub Copilot
     * @reason 将原版“在回调现场直接 render”的模型改为“后台发布状态，主线程协作渲染”。
     */
    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.REPORT_PROGRESS_WITH_TEXT,
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reportProgressWithText(final String text,
                                                    final float progress,
                                                    final CallbackInfo callbackInfo) {
        if (SaveProgressOverlayCoordinator.isReplayInProgress()) {
            return;
        }

        SaveProgressOverlayCoordinator.reportProgress(
                (com.fs.starfarer.campaign.save.CampaignSaveProgressDialog) (Object) this,
                text,
                progress
        );
        if (!SaveProgressOverlayCoordinator.hasActiveOpenGlContext()) {
            callbackInfo.cancel();
        }
    }

    /**
     * 发布不带文本的保存进度。
     *
     * @param progress 百分比进度值（0-100）
     * @author GitHub Copilot
     * @reason 保留原版回调的进度语义，同时彻底移除其中的 OpenGL/UI 渲染副作用。
     */
    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.REPORT_PROGRESS,
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reportProgress(final float progress,
                                            final CallbackInfo callbackInfo) {
        if (SaveProgressOverlayCoordinator.isReplayInProgress()) {
            return;
        }

        SaveProgressOverlayCoordinator.reportProgress(
                (com.fs.starfarer.campaign.save.CampaignSaveProgressDialog) (Object) this,
                progress
        );
        if (!SaveProgressOverlayCoordinator.hasActiveOpenGlContext()) {
            callbackInfo.cancel();
        }
    }
}