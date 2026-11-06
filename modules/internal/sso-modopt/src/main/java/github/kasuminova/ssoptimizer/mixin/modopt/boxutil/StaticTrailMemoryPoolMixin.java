package github.kasuminova.ssoptimizer.mixin.modopt.boxutil;

import github.kasuminova.ssoptimizer.modopt.boxutil.BoxUtilTrailWriteBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** 将 BoxUtil 持久映射尾迹缓冲的逻辑线程写入转交写入桥，避免与渲染线程的在飞绘制竞争。 */
@Mixin(targets = "org.boxutil.backends.core.statictrail.BUtil_StaticTrailMemoryPool")
public abstract class StaticTrailMemoryPoolMixin {

    @Redirect(method = "computeTrailNode", remap = false, expect = 1,
            at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;asIntBuffer()Ljava/nio/IntBuffer;", remap = false))
    private static IntBuffer sso$scratchViewForCompute(final ByteBuffer mapped) {
        return BoxUtilTrailWriteBridge.scratchViewFor(mapped);
    }

    @Redirect(method = "processCutTrailOnEntity", remap = false, expect = 1,
            at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;asIntBuffer()Ljava/nio/IntBuffer;", remap = false))
    private static IntBuffer sso$scratchViewForCut(final ByteBuffer mapped) {
        return BoxUtilTrailWriteBridge.scratchViewFor(mapped);
    }

    @Inject(method = "computeTrailNode", at = @At("RETURN"), remap = false)
    private static void sso$flushComputeScratch(final CallbackInfo ci) {
        BoxUtilTrailWriteBridge.flushCompute();
    }

    @Inject(method = "processCutTrailOnEntity", at = @At("RETURN"), remap = false)
    private static void sso$flushCutScratch(final CallbackInfo ci) {
        BoxUtilTrailWriteBridge.flushCut();
    }
}
