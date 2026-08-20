package github.kasuminova.ssoptimizer.mixin.save;

import com.fs.starfarer.campaign.save.CampaignSaveProgressDialog;
import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.OutputStream;

/**
 * 战役保存进度输出流 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.util.SaveProgressOutputStream}<br>
 * 注入动机：该类会在每次写够一定字节阈值后触发保存 UI 回调，是原版“写字节即 render UI”耦合的核心入口。<br>
 * 注入效果：构造时初始化新的进度会话；写出时只发布字节进度；关闭时标记完成，交由主线程渲染泵做最终收尾。
 */
@Mixin(targets = GameMixinSignatures.SaveProgressOutputStream.TARGET_CLASS)
public abstract class CampaignSaveProgressOutputStreamMixin {
    @Shadow(remap = false, aliases = GameMixinSignatures.SaveProgressOutputStream.WRITTEN_BYTES_FIELD)
    private volatile long ssoptimizer$writtenBytes;

    @Inject(method = GameMixinSignatures.SaveProgressOutputStream.PROGRESS_CONSTRUCTOR,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$beginSaveProgressPhase(final OutputStream delegate,
                                                    final long expectedBytes,
                                                    final float phaseStart,
                                                    final float phaseEnd,
                                                    final CampaignSaveProgressDialog progressDialog,
                                                    final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.beginStreamPhase(progressDialog, expectedBytes + 10_000L, phaseStart, phaseEnd);
    }

    @Inject(method = GameMixinSignatures.SaveProgressOutputStream.WRITE_BYTES,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$publishWrittenBytes(final byte[] bytes,
                                                 final int offset,
                                                 final int length,
                                                 final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.onBytesWritten(ssoptimizer$writtenBytes);
    }

    @Inject(method = GameMixinSignatures.SaveProgressOutputStream.CLOSE,
            at = @At("HEAD"), remap = false)
    private void ssoptimizer$completeSaveProgress(final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.complete();
    }
}