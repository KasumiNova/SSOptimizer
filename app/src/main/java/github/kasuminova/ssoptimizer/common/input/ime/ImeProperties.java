package github.kasuminova.ssoptimizer.common.input.ime;

import java.util.Locale;

/**
 * IME 功能的系统属性配置常量与读取工具。
 * <p>
 * 通过 {@code -Dssoptimizer.ime.*} JVM 参数控制 IME 的启用、后端选择和诊断日志。
 */
public final class ImeProperties {
    public static final String ENABLE_PROPERTY = "ssoptimizer.ime.enable";
    public static final String BACKEND_PROPERTY = "ssoptimizer.ime.backend";
    public static final String DIAGNOSTICS_PROPERTY = "ssoptimizer.ime.diagnostics";

    private ImeProperties() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    public static BackendMode backendMode() {
        final String configured = System.getProperty(BACKEND_PROPERTY, "auto");
        if (configured == null || configured.isBlank()) {
            return BackendMode.AUTO;
        }

        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "linux-xim" -> BackendMode.LINUX_XIM;
            case "windows-imm" -> BackendMode.WINDOWS_IMM;
            case "none" -> BackendMode.NONE;
            default -> BackendMode.AUTO;
        };
    }

    public static boolean diagnosticsEnabled() {
        return Boolean.parseBoolean(System.getProperty(DIAGNOSTICS_PROPERTY, "false"));
    }

    public enum BackendMode {
        AUTO,
        LINUX_XIM,
        WINDOWS_IMM,
        NONE
    }
}