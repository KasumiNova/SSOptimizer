package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.GlLedgerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 游戏 FBO 包装类的显存账本埋点。
 * <p>
 * 注入目标：{@code com.fs.graphics.FrameBufferObject}<br>
 * 注入动机：GL 显存分类账本（{@link github.kasuminova.ssoptimizer.common.loading.GlMemoryLedger}）
 * 需要计量非受管分配；该类 {@code createFramebuffer} 创建 POT 补齐的 RGBA8 颜色纹理，
 * 属非受管分配的第一类来源。<br>
 * 注入效果：{@code createFramebuffer(IIIIZ)Z} 返回 true 时按 POT 尺寸 ×4B（mipmaps ×4/3）
 * 计入 fboTex；{@code deleteFramebuffer()V} 进入时对称减去。
 * <p>
 * 锚点取舍：采用方法级 @Inject 而非对 {@code GL11.glTexImage2D} 调用点做 @Redirect——
 * 渲染线程分离模式下 RenderThreadRedirector 会把本类中 GL 调用点的 owner 改写为 bridge
 * 类，调用点锚定会在 RT 模式下失配；方法级锚点只依赖方法签名与 @Shadow 字段，
 * 不受 owner 改写影响。
 * <p>
 * 已知不准：named 源码显示本类 depth/stencil renderbuffer 字段从未被赋值（无实际分配），
 * 故只计颜色纹理；颜色纹理在 {@code bindFramebuffer} 后转入 Sprite 继续存活，
 * {@code deleteFramebuffer} 不真实删除纹理，账本按 FBO 包装生命周期计，存在低估窗口
 * （全游戏仅 3 个实例，影响可忽略）。
 */
@Mixin(targets = "com.fs.graphics.FrameBufferObject")
public abstract class FrameBufferObjectLedgerMixin {

    @Shadow(remap = false, aliases = "fboId")
    private int ssoptimizer$fboId;

    /**
     * FBO 颜色纹理分配计量。
     *
     * @author Kimi Code
     * @reason GL 显存分类账本：计量游戏 FBO 的 POT 补齐 RGBA8 颜色纹理。
     */
    @Inject(method = "createFramebuffer(IIIIZ)Z", at = @At("RETURN"), remap = false)
    private void ssoptimizer$ledgerCreate(final int x, final int y, final int w, final int h,
                                          final boolean mipmaps,
                                          final CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            GlLedgerHooks.noteVanillaFboCreated(ssoptimizer$fboId, w, h, mipmaps);
        }
    }

    /**
     * FBO 删除对称减量（HEAD 处 fboId 尚未清零）。
     *
     * @author Kimi Code
     * @reason 与 ssoptimizer$ledgerCreate 配对，维持账本平衡。
     */
    @Inject(method = "deleteFramebuffer()V", at = @At("HEAD"), remap = false)
    private void ssoptimizer$ledgerDelete(final CallbackInfo ci) {
        GlLedgerHooks.noteVanillaFboDeleted(ssoptimizer$fboId);
    }
}
