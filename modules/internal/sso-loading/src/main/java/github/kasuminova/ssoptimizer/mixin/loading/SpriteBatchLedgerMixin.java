package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.GlLedgerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 游戏 sprite/UI 批渲染 VBO 的显存账本埋点。
 * <p>
 * 注入目标：{@code com.fs.graphics.SpriteBatch}（fs.common_obf.jar）<br>
 * 注入动机：GL 显存分类账本 vbo 分类——该类 {@code allocateBuffers} 经
 * {@code ARBVertexBufferObject.glBufferDataARB} 分配固定尺寸批渲染缓冲
 * （maxQuads×4 顶点×8 float×4B），是游戏 jar 内唯一未计量的 GL 分配点
 * （静态扫描确认）。<br>
 * 注入效果：{@code allocateBuffers()V} RETURN 时按 vboId 登记字节数（同 id
 * 重复分配按替换计）；{@code allocateBuffers} HEAD 与 {@code destroy()V} HEAD
 * 处对称减去旧 buffer（两处均在内部分删除调用之前，此时 vboId 未失效）。<br>
 * <p>
 * 锚点取舍：采用方法级 @Inject 而非对 {@code glBufferDataARB} 调用点做
 * @Redirect——渲染线程分离模式下 RenderThreadRedirector 会把本类中 GL 调用点的
 * owner 改写为 bridge 类，调用点锚定会在 RT 模式下失配；方法级锚点只依赖方法
 * 签名与 @Shadow 字段，不受 owner 改写影响（与 FrameBufferObjectLedgerMixin 同理）。<br>
 * 计量口径：size 与字节码算式逐行一致（maxQuads×4×8×4）；守卫条件
 * {@code useVbo && vboId != -1} 与 allocateBuffers/destroy 内部的删除守卫一致。<br>
 * 已知不准：growCapacity 路径不含独立的 glBufferDataARB（只扩 scratch 缓冲），
 * 不重复入账。
 */
@Mixin(targets = "com.fs.graphics.SpriteBatch")
public abstract class SpriteBatchLedgerMixin {

    @Shadow(remap = false)
    private int vboId;

    @Shadow(remap = false)
    private int maxQuads;

    @Shadow(remap = false)
    private boolean useVbo;

    /**
     * 重分配前对称减去旧 VBO（allocateBuffers 内部先 glDeleteBuffersARB 旧 id 再生成新 id）。
     *
     * @author Kimi Code
     * @reason vbo 分类：维持 id 跟踪表平衡，防止重分配双计。
     */
    @Inject(method = "allocateBuffers", at = @At("HEAD"), remap = false)
    private void ssoptimizer$ledgerFreeOld(final CallbackInfo ci) {
        if (useVbo && vboId != -1) {
            GlLedgerHooks.noteBufferFreed(vboId);
        }
    }

    /**
     * VBO 分配计量（size = maxQuads×4 顶点×8 float×4B，与字节码算式一致）。
     *
     * @author Kimi Code
     * @reason vbo 分类：登记游戏 sprite 批渲染缓冲的显存占用。
     */
    @Inject(method = "allocateBuffers", at = @At("RETURN"), remap = false)
    private void ssoptimizer$ledgerAlloc(final CallbackInfo ci) {
        if (useVbo && vboId != -1) {
            GlLedgerHooks.noteBufferBytes(vboId, (long) maxQuads * 4 * 8 * 4);
        }
    }

    /**
     * destroy 对称减量（HEAD 处 vboId 未失效）。
     *
     * @author Kimi Code
     * @reason 与 ssoptimizer$ledgerAlloc 配对，维持账本平衡。
     */
    @Inject(method = "destroy", at = @At("HEAD"), remap = false)
    private void ssoptimizer$ledgerDestroy(final CallbackInfo ci) {
        if (useVbo && vboId != -1) {
            GlLedgerHooks.noteBufferFreed(vboId);
        }
    }
}
