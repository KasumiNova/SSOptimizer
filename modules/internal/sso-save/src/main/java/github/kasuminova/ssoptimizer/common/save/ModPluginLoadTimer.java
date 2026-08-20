package github.kasuminova.ssoptimizer.common.save;

import org.apache.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 读档后处理阶段的模组 {@code onGameLoad} 逐项计时记录器。
 * <p>
 * 动机：读档基准显示「载入阶段33→34」（模组插件 onGameLoad 循环 + 经济重算）约占全程 40%，
 * 但日志粒度只到秒，无法定位是哪个模组耗时。该记录器由
 * {@code CampaignGameManagerMixin} 在 {@code ModPlugin.onGameLoad} 调用点包一层计时，
 * 按插件类聚合并输出 TOP N，供 modopt 针对性优化提供数据。<br>
 * 仅在 {@code ssoptimizer.save.modloadtiming=true} 时启用（默认关，避免常态日志噪音）。
 */
public final class ModPluginLoadTimer {
    /** 启用模组 onGameLoad 逐项计时的系统属性。 */
    public static final String ENABLE_PROPERTY = "ssoptimizer.save.modloadtiming";

    private static final Logger LOGGER = Logger.getLogger(ModPluginLoadTimer.class);
    private static final int TOP_N = 15;

    private static volatile boolean enabled = Boolean.getBoolean(ENABLE_PROPERTY);
    private static final Map<String, long[]> TIMINGS = new LinkedHashMap<>();

    private ModPluginLoadTimer() {
    }

    /**
     * 是否启用计时。
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 记录一次 onGameLoad 调用耗时。
     *
     * @param pluginClass 插件实现类
     * @param nanos       调用耗时（纳秒）
     */
    public static void record(final Class<?> pluginClass, final long nanos) {
        TIMINGS.computeIfAbsent(pluginClass.getName(), key -> new long[2])[0] += nanos;
        TIMINGS.get(pluginClass.getName())[1]++;
    }

    /**
     * 输出汇总日志并重置（供下一轮读档重新统计）。
     */
    public static void dumpAndReset() {
        if (TIMINGS.isEmpty()) {
            return;
        }
        final StringBuilder sb = new StringBuilder("[SSO-Save] onGameLoad timings:");
        TIMINGS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(TOP_N)
                .forEach(e -> sb.append(String.format(Locale.ROOT, " %.0fms=%s(x%d)",
                        e.getValue()[0] / 1e6, e.getKey(), e.getValue()[1])));
        LOGGER.info(sb.toString());
        TIMINGS.clear();
    }
}
