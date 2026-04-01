package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.loading.specs.EngineSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 引擎所有者（Ship/Missile 的内部代理）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.ship.H$Oo}<br>
 * 注入动机：获取引擎所有者的引擎槽位列表、角速度、朝向等属性，
 * 用于批量计算所有引擎的渲染顶点。<br>
 * 注入效果：暴露 5 个 Invoker。</p>
 */
@Mixin(targets = "com.fs.starfarer.combat.entities.ship.H$Oo")
public interface GEngineOwnerAccessor {
    @Invoker(value = "getEngineLocations", remap = false)
    List<EngineSlot> ssoptimizer$getEngineLocations();

    @Invoker(value = "getAngularVelocity", remap = false)
    float ssoptimizer$getAngularVelocity();

    @Invoker(value = "getFacing", remap = false)
    float ssoptimizer$getFacing();

    @Invoker(value = "isMissile", remap = false)
    boolean ssoptimizer$isMissile();

    @Invoker(value = "isFighter", remap = false)
    boolean ssoptimizer$isFighter();
}