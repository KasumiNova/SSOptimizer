package github.kasuminova.ssoptimizer.mixin.accessor;

import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 引擎尾焰轨迹段（ContrailSegment）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.ContrailEngine$Oo}<br>
 * 注入动机：轨迹段存储了每个粒子的位置、法线、宽度、年龄进度和 UV 坐标等数据，
 * 原始字段名称为混淆名，需通过 Accessor 暴露给优化后的批量渲染管线。<br>
 * 注入效果：暴露 8 个只读访问器。</p>
 */
@Mixin(targets = "com.fs.starfarer.combat.entities.ContrailEngine$Oo")
public interface ContrailSegmentAccessor {
    @Accessor(value = "ø00000", remap = false)
    Vector2f ssoptimizer$getPosition();

    @Accessor(value = "return", remap = false)
    Vector2f ssoptimizer$getNormal();

    @Accessor(value = "Object", remap = false)
    float ssoptimizer$getWidth();

    @Accessor(value = "Ø00000", remap = false)
    float ssoptimizer$getMaxAge();

    @Accessor(value = "o00000", remap = false)
    float ssoptimizer$getProgress();

    @Accessor(value = "Ô00000", remap = false)
    float ssoptimizer$getAlphaMult();

    @Accessor(value = "Ò00000", remap = false)
    float ssoptimizer$getU();
}