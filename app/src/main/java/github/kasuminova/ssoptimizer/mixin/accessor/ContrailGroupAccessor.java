package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.loading.specs.EngineSlot;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.awt.*;
import java.util.List;

/**
 * 引擎尾焰轨迹组（ContrailGroup）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.ContrailEngine$o}<br>
 * 注入动机：原始类字段为混淆名称，无法直接访问；需要读取轨迹段列表、纹理、尾部位置、
 * 颜色和混合模式等属性以实现自定义渲染管线。<br>
 * 注入效果：暴露 5 个只读访问器，供 {@code ContrailGroupRunner} 等渲染优化类使用。</p>
 */
@Mixin(targets = "com.fs.starfarer.combat.entities.ContrailEngine$o")
public interface ContrailGroupAccessor {
    @Accessor(value = "oO0000", remap = false)
    List<Object> ssoptimizer$getSegments();

    @Accessor(value = "ÒO0000", remap = false)
    com.fs.graphics.Object ssoptimizer$getTexture();

    @Accessor(value = "for", remap = false)
    Vector2f ssoptimizer$getTail();

    @Accessor(value = "Õ00000", remap = false)
    Color ssoptimizer$getColor();

    @Accessor(value = "ö00000", remap = false)
    EngineSlot.BlendMode ssoptimizer$getBlendMode();
}