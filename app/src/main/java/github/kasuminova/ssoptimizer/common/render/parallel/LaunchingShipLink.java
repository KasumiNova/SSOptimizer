package github.kasuminova.ssoptimizer.common.render.parallel;

/**
 * 「舰载机→母舰」关联的注入接口：由 {@code ShipLaunchLinkMixin} 注入到游戏
 * Ship 类（{@code getLaunchingShip()} 的委托），供 {@link ParallelLayerRenderer}
 * 分片分组使用（同组进同段——{@code Ship.renderShadow} 会跨实体写母舰的
 * clipToShip 状态）。
 * <p>
 * 存在意义：named jar 的 Ship 类携带 JVM 规范外的字段名（反混淆产物），
 * 单测环境无法加载游戏类——面向本接口编程使编排器与单测都不直接触碰
 * 游戏类型。
 */
public interface LaunchingShipLink {
    /**
     * 舰载机的母舰（发射舰）；非舰载机为 null。
     *
     * @return 母舰渲染物（游戏 Ship 实例），无则 null
     */
    Object ssoptimizer$getLaunchingShip();
}
