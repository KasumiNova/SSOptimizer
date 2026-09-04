package github.kasuminova.ssoptimizer.common.campaign;

import org.apache.log4j.Logger;

/**
 * {@code TacticalModule.advance}「Looking at other fleets」扫描的距离预过滤 helper。
 * <p>
 * 职责：在每舰队 {@code getVisibilityLevelTo} 精判之前做距离粗过滤，
 * 把「精判必然返回 {@link com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel#NONE}」
 * 的舰队直接跳过，压低 interval 内 O(F²) 扫描的精判开销。<br>
 * 保守性依据（named 源码 {@code BaseCampaignEntity.getVisibilityLevelTo} 实证）：
 * 精判的距离出口为
 * {@code max(0, centerDist - targetRadius - viewerRadius) > sensorRangeMaxForLocation(loc) + target.getExtendedDetectedAtRange()}
 * 时必然返回 NONE，且循环体对 NONE 舰队无任何副作用（纯查询，无状态写回）。
 * 本 helper 逐字复刻该不等式，过滤半径与原版完全一致——不是「宁宽」的近似，而是等价剪枝。<br>
 * 调用方（Mixin）额外兜底两种距离无关的非 NONE 出口，命中时不做过滤：
 * <ul>
 *   <li>目标舰队 {@code hasSensorProfile() == false}（精判直接返回最高可见级）；</li>
 *   <li>战役传感器全局关闭且观察方为玩家舰队
 *       （{@code !isCampaignSensorsOn() && viewer.isPlayerFleet()} 时精判无视距离）。</li>
 * </ul>
 * 回退开关：{@value #ENABLED_PROPERTY}=true 才启用（默认 false=关闭，
 * 关闭时 Mixin 返回原版列表零改动）。<br>
 * 线程模型：仅在战役主线程由 {@code TacticalModule.advance} 调用，实现单线程确定。
 */
public final class TacticalVisibilityPrefilter {
    /** 预过滤开关系统属性名。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.campaign.tacticalPrefilter";
    /** 默认关闭：改变 AI 感知路径，需显式开启。 */
    public static final boolean DEFAULT_ENABLED = false;

    private static final Logger LOGGER = Logger.getLogger(TacticalVisibilityPrefilter.class);

    private static final boolean ENABLED = parseEnabled(System.getProperty(ENABLED_PROPERTY));

    private TacticalVisibilityPrefilter() {
    }

    /**
     * @return 预过滤是否启用（类初始化时解析一次）
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 解析开关属性值。
     * <p>
     * 未设置时返回 {@link #DEFAULT_ENABLED}；仅识别 {@code "true"}/{@code "false"}
     * （大小写不敏感），其他取值按默认值处理并记 WARN 日志。
     *
     * @param raw 属性原始值（可为 {@code null}）
     * @return 生效的开关值
     */
    public static boolean parseEnabled(final String raw) {
        if (raw == null) {
            return DEFAULT_ENABLED;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        LOGGER.warn("[SSOptimizer] " + ENABLED_PROPERTY + " 取值 \"" + raw
                + "\" 无法识别（仅支持 true/false），按默认 " + DEFAULT_ENABLED + " 处理");
        return DEFAULT_ENABLED;
    }

    /**
     * 复刻原版精判的距离 NONE 出口：返回 true 时
     * {@code getVisibilityLevelTo} 必然返回 NONE（在调用方已排除
     * hasSensorProfile==false 与传感器全局关闭两个距离无关出口的前提下）。
     *
     * @param centerDist               两舰队中心距离（{@code Utils.getDistance} 原值）
     * @param targetRadius             被观察舰队半径
     * @param viewerRadius             观察方舰队半径
     * @param sensorRangeMax           {@code StarfarerSettings.getSensorRangeMaxForLocation} 原值
     * @param extendedDetectedAtRange  被观察舰队 {@code getExtendedDetectedAtRange} 原值
     * @return 精判必然返回 NONE 时返回 true
     */
    public static boolean isDefinitelyInvisible(final float centerDist,
                                                final float targetRadius,
                                                final float viewerRadius,
                                                final float sensorRangeMax,
                                                final float extendedDetectedAtRange) {
        float adjusted = centerDist - targetRadius - viewerRadius;
        if (adjusted < 0.0F) {
            adjusted = 0.0F;
        }
        return adjusted > sensorRangeMax + extendedDetectedAtRange;
    }
}
