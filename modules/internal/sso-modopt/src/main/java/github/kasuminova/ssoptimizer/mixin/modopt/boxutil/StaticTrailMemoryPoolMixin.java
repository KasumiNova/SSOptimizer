package github.kasuminova.ssoptimizer.mixin.modopt.boxutil;

import github.kasuminova.ssoptimizer.modopt.boxutil.BoxUtilTrailWriteBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * BoxUtil 静态尾迹池的 RT 模式跨帧竞争修复。
 * <p>
 * 注入目标：{@code org.boxutil.backends.core.statictrail.BUtil_StaticTrailMemoryPool}
 * （外部模组 BoxUtil 1.6+；模组不存在时 Mixin 记 WARN 跳过）。<br>
 * 注入动机：RT 模式下渲染线程执行第 N 帧绘制的同时，BoxUtil 逻辑线程已在推进
 * 第 N+1 帧并向持久映射 VBO 直写尾迹节点——写入不经任何 GL 调用，录制层无法感知，
 * 与在飞绘制并发读到写了一半的节点数据（实机表现：trail 偶发撕裂成大块不规则
 * 三角形闪烁）。<br>
 * 注入效果：{@code computeTrailNode}/{@code processCutTrailOnEntity} 内对映射缓冲
 * {@code asIntBuffer()} 的取视图调用改向到等容 CPU 暂存缓冲，方法返回时把暂存内容
 * 全量经 {@code MappedWriteBridge} 排入渲染流——写入在渲染线程上、上一帧帧尾 fence
 * 通过后落笔（机制详述见 {@link BoxUtilTrailWriteBridge}）。非 RT 模式（服务未注册）
 * redirect 原样返回真实映射视图，行为与原版一致。<br>
 * 覆盖边界：仅持久映射路径；非持久回退路径（{@code glBufferSubData} 直传）在 GL&lt;4.4
 * 环境才有，且其写入经 bridge 录制/旁路仍可能乱序——目标环境（持久映射可用）不涉及，
 * 见 {@code docs/design/mapped-write-bridge.md}。
 */
@Mixin(targets = "org.boxutil.backends.core.statictrail.BUtil_StaticTrailMemoryPool")
public abstract class StaticTrailMemoryPoolMixin {

    /**
     * 计算路径的写入视图改向。
     *
     * @param mapped 池真实映射缓冲本体
     * @return 本轮节点写入的目标视图（RT 模式为 scratch，非 RT 为真实映射视图）
     * @reason 逻辑线程直写映射内存与渲染线程执行上一帧绘制并发，是 trail 撕裂根因
     */
    @Redirect(method = "computeTrailNode", remap = false, expect = 1,
            at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;asIntBuffer()Ljava/nio/IntBuffer;", remap = false))
    private static IntBuffer sso$scratchViewForCompute(final ByteBuffer mapped) {
        return BoxUtilTrailWriteBridge.scratchViewFor(mapped);
    }

    /**
     * 截断路径的写入视图改向（主线程渲染期调用，与上一帧在飞绘制同样存在跨帧竞争）。
     *
     * @param mapped 池真实映射缓冲本体
     * @return 本轮截断写入的目标视图
     * @reason 同 {@link #sso$scratchViewForCompute}
     */
    @Redirect(method = "processCutTrailOnEntity", remap = false, expect = 1,
            at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;asIntBuffer()Ljava/nio/IntBuffer;", remap = false))
    private static IntBuffer sso$scratchViewForCut(final ByteBuffer mapped) {
        return BoxUtilTrailWriteBridge.scratchViewFor(mapped);
    }

    /**
     * 计算路径返回点：把本轮写入的 scratch 全量排入渲染流（双逻辑线程去重）。
     *
     * @reason 写入必须经渲染流获得相对绘制命令的流序与 GPU 序
     */
    @Inject(method = "computeTrailNode", at = @At("RETURN"), remap = false)
    private static void sso$flushComputeScratch(final CallbackInfo ci) {
        BoxUtilTrailWriteBridge.flushCompute();
    }

    /**
     * 截断路径返回点：把本轮写入的 scratch 全量排入渲染流（单线程路径逐槽提交）。
     *
     * @reason 同 {@link #sso$flushComputeScratch}
     */
    @Inject(method = "processCutTrailOnEntity", at = @At("RETURN"), remap = false)
    private static void sso$flushCutScratch(final CallbackInfo ci) {
        BoxUtilTrailWriteBridge.flushCut();
    }
}
