package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.common.save.UnmarshalPhaseTimer;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 战役读档进度输入流 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.util.SaveProgressInputStream}<br>
 * 注入动机：读档进度的原版驱动是该流在读取中调用
 * {@code CampaignSaveProgressDialog.reportProgress("Loading...", pct)}（{@code updateProgress}
 * 自带约 60fps 节流）。对话框 Mixin 已把 reportProgress 改为「发布状态 + 无上下文线程取消
 * 内联渲染」，但读档路径没有任何泵触发点（保存路径的泵挂在 QueuedXmlStreamWriter 上），
 * RT 模式下读档全程无回放调度——进度界面整段不渲染，画面冻结到读档完成。<br>
 * 注入效果：{@code updateProgress} 返回后补发一次回放泵（协调器内部有序列号/节流去重，
 * 被 updateProgress 自身节流跳过的调用不会重复渲染）；{@code markComplete} 后标记完成
 * 并立即泵出 100% 收尾帧，避免最后一帧滞留到下一次存读档。
 */
@Mixin(targets = GameMixinSignatures.SaveProgressInputStream.TARGET_CLASS)
public abstract class CampaignSaveProgressInputStreamMixin {
    @Inject(method = GameMixinSignatures.SaveProgressInputStream.UPDATE_PROGRESS,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$pumpLoadProgress(final boolean force,
                                              final CallbackInfo callbackInfo) {
        if (force) {
            UnmarshalPhaseTimer.begin();
        }
        SaveProgressOverlayCoordinator.maybePumpFrame();
    }

    @Inject(method = GameMixinSignatures.SaveProgressInputStream.MARK_COMPLETE,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$completeLoadProgress(final CallbackInfo callbackInfo) {
        UnmarshalPhaseTimer.end();
        SaveProgressOverlayCoordinator.complete();
        SaveProgressOverlayCoordinator.maybePumpFrame();
    }
}
