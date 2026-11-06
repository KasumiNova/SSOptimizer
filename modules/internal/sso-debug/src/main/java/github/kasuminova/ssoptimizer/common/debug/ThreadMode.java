package github.kasuminova.ssoptimizer.common.debug;

import java.util.Locale;

/** MAIN 与游戏主循环串行，NEW 在虚拟线程执行。 */
public enum ThreadMode {
    NEW,

    MAIN;

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
