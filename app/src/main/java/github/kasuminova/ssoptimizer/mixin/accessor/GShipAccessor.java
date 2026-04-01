package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.systems.F;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 舰船实体（Ship）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.Ship}<br>
 * 注入动机：获取舰船的舰船系统（ShipSystem）引用，用于判断引擎加速模式等渲染状态。<br>
 * 注入效果：暴露 1 个 Invoker。</p>
 */
@Mixin(targets = "com.fs.starfarer.combat.entities.Ship")
public interface GShipAccessor {
    @Invoker(value = "getSystem", remap = false)
    F ssoptimizer$getSystem();
}