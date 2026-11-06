package github.kasuminova.ssoptimizer.common.campaign;

import org.apache.log4j.Logger;

/**
 * 在舰队可见性精判前按原版距离出口剪枝，仅限战役主线程调用。
 * 调用方必须先排除无传感器轮廓的目标，以及传感器全局关闭时的玩家观察方。
 */
public final class TacticalVisibilityPrefilter {
    public static final String ENABLED_PROPERTY = "ssoptimizer.campaign.tacticalPrefilter";
    public static final boolean DEFAULT_ENABLED = false;

    private static final Logger LOGGER = Logger.getLogger(TacticalVisibilityPrefilter.class);

    private static final boolean ENABLED = parseEnabled(System.getProperty(ENABLED_PROPERTY));

    private TacticalVisibilityPrefilter() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

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

    /** 仅在原版距离判定必然不可见时返回 true，调用方须先排除两个距离无关出口。 */
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
