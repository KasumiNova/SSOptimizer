package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.entities.G;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.awt.*;

/**
 * 引擎槽位（EngineSlot）的 Mixin Accessor / Invoker。
 *
 * <p>注入目标：{@code com.fs.starfarer.loading.specs.EngineSlot}<br>
 * 注入动机：引擎槽位定义了舰船引擎的几何参数（位置、角度、尺寸）和视觉属性（颜色、辉光），
 * 优化后的尾焰渲染管线需要直接读取这些属性来批量计算顶点数据。<br>
 * 注入效果：暴露 13 个 Invoker，覆盖位置计算、颜色获取、尺寸查询和状态判断。</p>
 */
@Mixin(targets = "com.fs.starfarer.loading.specs.EngineSlot")
public interface EngineSlotAccessor {
    @Invoker(value = "isOmegaMode", remap = false)
    boolean ssoptimizer$isOmegaMode();

    @Invoker(value = "isWithSpread", remap = false)
    boolean ssoptimizer$isWithSpread();

    @Invoker(value = "isSystemActivated", remap = false)
    boolean ssoptimizer$isSystemActivated();

    @Invoker(value = "computeMidArcAngle", remap = false)
    float ssoptimizer$computeMidArcAngle(float facing);

    @Invoker(value = "computePosition", remap = false)
    Vector2f ssoptimizer$computePosition(Vector2f destination, float facing);

    @Invoker(value = "getMaxSpread", remap = false)
    float ssoptimizer$getMaxSpread();

    @Invoker(value = "getColor", remap = false)
    Color ssoptimizer$getColor();

    @Invoker(value = "getLength", remap = false)
    float ssoptimizer$getLength();

    @Invoker(value = "getWidth", remap = false)
    float ssoptimizer$getWidth();

    @Invoker(value = "getGlowType", remap = false)
    G.o ssoptimizer$getGlowType();

    @Invoker(value = "getGlowAlternateColor", remap = false)
    Color ssoptimizer$getGlowAlternateColor();

    @Invoker(value = "getGlowSizeMult", remap = false)
    float ssoptimizer$getGlowSizeMult();
}