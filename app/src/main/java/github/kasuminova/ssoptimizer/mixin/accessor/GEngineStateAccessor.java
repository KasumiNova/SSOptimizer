package github.kasuminova.ssoptimizer.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 引擎状态（EngineState）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.G$Oo}<br>
 * 注入动机：引擎状态包含纹理 U 坐标、核心旋转角度、扩散值和火焰等级，
 * 是每帧渲染时的关键动态参数。<br>
 * 注入效果：暴露 4 个只读 Accessor。</p>
 */
@Mixin(targets = "com.fs.starfarer.combat.entities.G$Oo")
public interface GEngineStateAccessor {
    @Accessor(value = "Ø00000", remap = false)
    float ssoptimizer$getTexU();

    @Accessor(value = "Ò00000", remap = false)
    float ssoptimizer$getCoreRotation();

    @Accessor(value = "return", remap = false)
    float ssoptimizer$getSpread();

    @Accessor(value = "Õ00000", remap = false)
    float ssoptimizer$getFlameLevel();
}