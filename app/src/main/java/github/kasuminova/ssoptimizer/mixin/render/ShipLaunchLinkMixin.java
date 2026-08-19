package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.common.render.parallel.LaunchingShipLink;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 给游戏 Ship 类注入 {@link LaunchingShipLink}：把公开方法
 * {@code getLaunchingShip()}（舰载机的发射舰）以接口形式暴露给
 * {@code ParallelLayerRenderer} 的分片分组（舰载机与母舰同段）。
 */
@Mixin(targets = GameClassNames.SHIP_DOTTED)
public abstract class ShipLaunchLinkMixin implements LaunchingShipLink {
    @Shadow(remap = false)
    public abstract Ship getLaunchingShip();

    @Override
    public Object ssoptimizer$getLaunchingShip() {
        return getLaunchingShip();
    }
}
