package github.kasuminova.ssoptimizer.mixin.accessor;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * {@code LayeredRenderer}（com.fs.graphics.LayeredRenderer，DoNotObfuscate
 * 名字稳定）的 Mixin Accessor：暴露层→渲染物列表簿记与层枚举类，供
 * {@code ParallelLayerRenderer} 在不改动游戏类的前提下重编排层遍历
 * （实体级并行录制，审计 §6）。
 */
@Mixin(targets = GameClassNames.LAYERED_RENDERER_DOTTED)
public interface LayeredRendererAccessor {
    /** 层到渲染物列表的映射（原始泛型 {@code Map<T, List<LayeredRenderable<T, V>>>}）。 */
    @Accessor(value = "layers", remap = false)
    Map<?, ?> ssoptimizer$getLayers();

    /** 层枚举类（{@code EnumSet.allOf} 的遍历序来源）。 */
    @Accessor(value = "layerEnumClass", remap = false)
    Class<?> ssoptimizer$getLayerEnumClass();
}
