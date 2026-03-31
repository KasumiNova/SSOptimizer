package github.kasuminova.ssoptimizer.input.ime;

import java.util.Locale;

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