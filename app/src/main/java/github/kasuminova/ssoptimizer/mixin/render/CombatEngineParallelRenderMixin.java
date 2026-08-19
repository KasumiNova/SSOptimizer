package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.LayeredRenderer;
import github.kasuminova.ssoptimizer.common.render.parallel.ParallelLayerRenderer;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code CombatEngine.render(boolean)} 内 {@code renderer.renderExcluding(...)}
 * 调用（17 层遍历，并行主区）的转接：交给 {@link ParallelLayerRenderer}
 * 做实体级分片并行录制；底粒子组（调用前）、debris/12 粒子系统/renderOnly×3/
 * 浮动文字（调用后）保持原方法内的串行位置不动。
 * <p>
 * 选择 @Redirect 而非 @Overwrite：只替换层遍历这一个调用点，前后串行段
 * （含 Profiler 埋点）保持原版字节码不动，后续游戏版本微调方法体时
 * 冲突面最小。
 */
@Mixin(targets = GameClassNames.COMBAT_ENGINE_DOTTED)
public abstract class CombatEngineParallelRenderMixin {
    /**
     * @param renderer 战斗引擎分层渲染器（this.renderer）
     * @param viewport 当前视口（this.viewport）
     * @param excluded 排除层（ABOVE_PARTICLES / ABOVE_PARTICLES_LOWER / JUST_BELOW_WIDGETS）
     */
    @Redirect(method = "render(Z)V", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/graphics/LayeredRenderer;renderExcluding(Ljava/lang/Object;[Ljava/lang/Enum;)V"))
    private void ssoptimizer$renderExcludingParallel(LayeredRenderer renderer, Object viewport, Enum[] excluded) {
        ParallelLayerRenderer.renderExcluding(renderer, viewport, excluded);
    }
}
