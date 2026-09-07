package github.kasuminova.ssoptimizer.common.debug;

import java.util.Locale;

/**
 * 脚本执行线程模式。
 *
 * <p>动机：只读采样类脚本不应阻塞游戏主循环（默认 {@link #NEW}）；
 * 读写游戏业务状态的脚本必须与主循环串行以规避竞态（{@link #MAIN}，
 * 投递到主线程下一帧执行）。</p>
 */
public enum ThreadMode {
    /** 专用新线程执行（虚拟线程），不阻塞游戏主循环。 */
    NEW,

    /** 投递到游戏主线程下一帧执行，与游戏逻辑串行。 */
    MAIN;

    /**
     * 解析 RPC 参数中的 threadMode 字符串（null/空白 → {@link #NEW}）。
     *
     * @param value 参数字符串
     * @return 线程模式
     */
    public static ThreadMode parse(final String value) {
        if (value == null || value.isBlank()) {
            return NEW;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "new" -> NEW;
            case "main" -> MAIN;
            default -> throw new IllegalArgumentException(
                    "[SSOptimizer] Unknown threadMode '" + value + "' (valid: new, main)");
        };
    }
}
