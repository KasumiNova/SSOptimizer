package github.kasuminova.ssoptimizer.mixin.accessor;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 引擎尾焰轨迹段（ContrailSegment）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.ContrailEngine$ContrailSegment}<br>
 * 注入动机：渲染管线读取位置/法线/宽度/年龄/UV 等属性；{@code ContrailAdvanceHelper}
 * 需要写回 texU/progress/width（段更新公式的产物）并读取 vel/baseWidth
 * （advance 的位置推进与宽度公式输入）。</p>
 */
@Mixin(targets = GameClassNames.CONTRAIL_SEGMENT_DOTTED)
public interface ContrailSegmentAccessor {
    @Accessor(value = "position", remap = false)
    Vector2f ssoptimizer$getPosition();

    @Accessor(value = "normal", remap = false)
    Vector2f ssoptimizer$getNormal();

    @Accessor(value = "vel", remap = false)
    Vector2f ssoptimizer$getVel();

    @Accessor(value = "width", remap = false)
    float ssoptimizer$getWidth();

    @Accessor(value = "width", remap = false)
    void ssoptimizer$setWidth(float width);

    @Accessor(value = "baseWidth", remap = false)
    float ssoptimizer$getBaseWidth();

    @Accessor(value = "maxAge", remap = false)
    float ssoptimizer$getMaxAge();

    @Accessor(value = "progress", remap = false)
    float ssoptimizer$getProgress();

    @Accessor(value = "progress", remap = false)
    void ssoptimizer$setProgress(float progress);

    @Accessor(value = "alphaMult", remap = false)
    float ssoptimizer$getAlphaMult();

    @Accessor(value = "texU", remap = false)
    float ssoptimizer$getU();

    @Accessor(value = "texU", remap = false)
    void ssoptimizer$setTexU(float texU);
}
