package github.kasuminova.ssoptimizer.common.input.ime;

import java.util.Locale;

/**
 * 输入法后端工厂。
 * <p>
 * 根据当前操作系统和 {@link ImeProperties} 配置选择合适的 {@link ImeBackend} 实现。
 */
public final class ImeBackends {
    private ImeBackends() {
    }

    public static ImeBackend createDefault() {
        return create(new LinuxXimImeBackend(), new WindowsImmImeBackend());
    }

    public static ImeBackend create(final ImeBackend linuxBackend,
                                    final ImeBackend windowsBackend) {
        if (!ImeProperties.isEnabled()) {
            final ImeBackend backend = new NoopImeBackend();
            ImeDiagnostics.logBackendSelection(ImeProperties.backendMode(), false, backend);
            return backend;
        }

        final ImeProperties.BackendMode mode = ImeProperties.backendMode();
        final ImeBackend backend = switch (mode) {
            case NONE -> new NoopImeBackend();
            case LINUX_XIM -> linuxBackend != null ? linuxBackend : new NoopImeBackend();
            case WINDOWS_IMM -> windowsBackend != null ? windowsBackend : new NoopImeBackend();
            case AUTO -> autoSelect(linuxBackend, windowsBackend);
        };
        ImeDiagnostics.logBackendSelection(mode, true, backend);
        return backend;
    }

    private static ImeBackend autoSelect(final ImeBackend linuxBackend,
                                         final ImeBackend windowsBackend) {
        if (isLinux() && linuxBackend != null && linuxBackend.isAvailable()) {
            return linuxBackend;
        }
        if (isWindows() && windowsBackend != null && windowsBackend.isAvailable()) {
            return windowsBackend;
        }
        return new NoopImeBackend();
    }

    private static boolean isLinux() {
        return osName().contains("linux");
    }

    private static boolean isWindows() {
        return osName().contains("windows");
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }
}