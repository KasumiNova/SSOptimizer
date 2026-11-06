package github.kasuminova.ssoptimizer.common.input.ime;

import org.apache.log4j.Logger;

public final class WindowsDisplayImeHooks {
    private static final Logger LOGGER = Logger.getLogger(WindowsDisplayImeHooks.class);

    private static volatile long windowHandle;

    private WindowsDisplayImeHooks() {
    }

    public static void afterCreateKeyboard(final long hwnd) {
        windowHandle = hwnd;
        final ImeCoordinator coordinator = ImeCoordinator.getInstance();
        if (!(coordinator.backend() instanceof WindowsImmImeBackend windowsBackend) || hwnd == 0L) {
            return;
        }
        attach(windowsBackend, coordinator, hwnd);
    }

    public static void beforeDestroyKeyboard(final Object windowsDisplay) {
        windowHandle = 0L;
        final ImeBackend backend = ImeCoordinator.getInstance().backend();
        try {
            backend.detach();
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to detach Windows IME before keyboard destroy: " + t.getMessage());
        }
    }

    public static void onUpdate() {
        try {
            ImeCoordinator.getInstance().pollAndApplyCommittedText();
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to poll Windows IME committed text: " + t.getMessage());
        }
    }

    public static void onFocusChanged(final Object windowsDisplay,
                                      final boolean gotFocus) {
        try {
            final ImeCoordinator coordinator = ImeCoordinator.getInstance();
            if (gotFocus) {
                ensureAttached(coordinator.backend(), coordinator);
            }
            coordinator.onWindowFocusChanged(gotFocus);
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to update Windows IME focus state: " + t.getMessage());
        }
    }

    public static void ensureAttached(final ImeBackend backend,
                                      final ImeCoordinator coordinator) {
        if (!(backend instanceof WindowsImmImeBackend windowsBackend) || !windowsBackend.isAvailable()) {
            return;
        }
        if (windowsBackend.hasActiveContext()) {
            return;
        }
        final long hwnd = windowHandle;
        if (hwnd == 0L) {
            return;
        }
        attach(windowsBackend, coordinator, hwnd);
    }

    private static void attach(final WindowsImmImeBackend backend,
                               final ImeCoordinator coordinator,
                               final long hwnd) {
        try {
            backend.attach(0L, hwnd);
            final ImeCaretRect rect = coordinator.computeCurrentCaretRect();
            if (rect != null) {
                backend.updateSpot(rect);
            }
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to attach Windows IME: " + t.getMessage());
        }
    }
}
