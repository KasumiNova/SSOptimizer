package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.combat.CombatViewport;
import com.fs.starfarer.combat.systems.EmpSystem;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EMP 电弧实体（EmpArcEntity）渲染空值守卫。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.systems.EmpArcEntity#render}<br>
 * 注入动机：{@code setTargetToShipCenter}（模组经 API 调用）先把 {@code target} 置 null
 * 再重算，且目标点超出 maxRange 时会保持 null 不再赋值；原版 render 对
 * {@code target} 无任何判空。单线程原版中 render 与逻辑调用串行，观察不到中间态；
 * 渲染线程（RT）模式下 render 与逻辑线程并发执行，可在置 null 窗口内读取到
 * null 并抛出 NPE（实机堆栈见 render 内 {@code target.computeOffset} 调用点）。<br>
 * 注入效果：render 入口检测 {@code target == null} 时取消本帧渲染——与原版语义等价
 * （原版永远不会观察到该状态），至多丢失一帧电弧画面，不可感知。<br>
 * 注：{@code advance}/{@code doDamage} 路径与模组调用同在逻辑线程，无竞态，不做改动。
 */
@Mixin(targets = GameClassNames.EMP_ARC_ENTITY_DOTTED)
public abstract class EmpArcEntityRenderMixin {
    @Shadow(remap = false)
    private EmpSystem.EmpArcTarget target;

    /**
     * render 入口空值守卫。
     *
     * @param layer 渲染层
     * @param viewport 战斗视口
     * @param ci 回调（target 为 null 时取消本帧渲染）
     * @author KasumiNova
     * @reason RT 模式下 render 与 setTargetToShipCenter 并发，原版无判空。
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$guardNullTarget(final CombatEngineLayers layer,
                                             final CombatViewport viewport,
                                             final CallbackInfo ci) {
        if (this.target == null) {
            ci.cancel();
        }
    }
}
