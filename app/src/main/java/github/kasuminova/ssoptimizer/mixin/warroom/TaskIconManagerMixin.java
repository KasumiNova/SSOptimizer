package github.kasuminova.ssoptimizer.mixin.warroom;

import github.kasuminova.ssoptimizer.common.render.engine.TexturedStripRenderHelper;
import github.kasuminova.ssoptimizer.common.render.warroom.WarroomTaskLineBatch;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 指挥界面任务连线渲染的帧内合批注入。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.warroom.TaskIconManager#render(float, float)}<br>
 * 注入动机：该方法内每个任务图标固定发起 18 次 {@code renderTexturedStrip}
 * （3 条并行连线 × 2 段箭头 × 3 层偏移阴影），每次调用都独立执行纹理绑定、
 * JNI 调用与 draw call；且已摧毁舰船残留在 {@code taskIconList} 中的图标
 * 每帧仍以 0 透明度完整渲染其连线，连线总数随累计部署舰船数持续增长。<br>
 * 注入效果：HEAD 处开启 {@link WarroomTaskLineBatch} 帧内收集，RETURN 处结束并
 * 单次提交（一次纹理绑定 + 一次 draw call）；收集期间完全透明的条带被直接剔除。
 * 收集区间内的条带渲染经由 {@code render.TexturedStripRendererMixin} 委托到
 * {@link TexturedStripRenderHelper}，由其检测收集状态转入批量路径。<br>
 * 默认开启，可通过 {@code -Dssoptimizer.render.warroomtasks.enable=false} 关闭。
 */
@Mixin(targets = GameClassNames.TASK_ICON_MANAGER_DOTTED)
public abstract class TaskIconManagerMixin {

    /**
     * 在任务图标渲染前开启帧内条带收集。
     *
     * @param screenScale 小地图缩放（原版参数，未使用）
     * @param alpha       整体透明度（原版参数，未使用）
     * @param ci          回调
     */
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ssoptimizer$beginTaskLineBatch(final float screenScale, final float alpha, final CallbackInfo ci) {
        TexturedStripRenderHelper.beginStripBatch();
    }

    /**
     * 在任务图标渲染后结束收集并一次性提交批次。
     *
     * @param screenScale 小地图缩放（原版参数，未使用）
     * @param alpha       整体透明度（原版参数，未使用）
     * @param ci          回调
     */
    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void ssoptimizer$endTaskLineBatch(final float screenScale, final float alpha, final CallbackInfo ci) {
        TexturedStripRenderHelper.endStripBatch();
    }
}
