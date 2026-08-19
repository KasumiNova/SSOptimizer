package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.entities.ContrailEngine;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.awt.*;
import java.util.List;

/**
 * 引擎尾焰轨迹组（ContrailGroup）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.ContrailEngine$ContrailGroup}<br>
 * 注入动机：暴露轨迹段容器、纹理、尾点、颜色、混合模式等只读属性供批量渲染管线
 * 使用；暴露 {@code ended}/{@code widthMode}/{@code widthMultiplier}/
 * {@code segmentDuration}/{@code key} 供 {@code ContrailAdvanceHelper} 的段更新
 * 使用（组内恒量提升）；{@code removeExpiredSegment} 以 {@link Invoker} 暴露给
 * advance 优化路径调用（原版死亡判定逻辑不重复实现）。</p>
 */
@Mixin(targets = GameClassNames.CONTRAIL_GROUP_DOTTED)
public interface ContrailGroupAccessor {
    @Accessor(value = "segments", remap = false)
    List<Object> ssoptimizer$getSegments();

    @Accessor(value = "texture", remap = false)
    com.fs.graphics.TextureObject ssoptimizer$getTexture();

    @Accessor(value = "tail", remap = false)
    Vector2f ssoptimizer$getTail();

    @Accessor(value = "color", remap = false)
    Color ssoptimizer$getColor();

    @Accessor(value = "blendMode", remap = false)
    EngineSlot.BlendMode ssoptimizer$getBlendMode();

    @Accessor(value = "ended", remap = false)
    boolean ssoptimizer$getEnded();

    @Accessor(value = "widthMode", remap = false)
    ContrailEngine.ContrailWidthMode ssoptimizer$getWidthMode();

    @Accessor(value = "widthMultiplier", remap = false)
    float ssoptimizer$getWidthMultiplier();

    /** 组内全部段的 maxAge 恒等于该值（原版 addSegment 恒以 segmentDuration 赋值段 maxAge）。 */
    @Accessor(value = "segmentDuration", remap = false)
    float ssoptimizer$getSegmentDuration();

    @Accessor(value = "key", remap = false)
    Object ssoptimizer$getKey();

    /**
     * 段死亡判定 + 头段移除（原版 {@code ContrailGroup.removeExpiredSegment()}）：
     * 头两段均已完全老化时移除头段并归还 true，否则把头段 alphaMult 清零并返回
     * false。由 {@code ContrailAdvanceHelper} 调用，原逻辑不重复实现。
     */
    @Invoker(value = "removeExpiredSegment", remap = false)
    boolean ssoptimizer$removeExpiredSegment();
}
