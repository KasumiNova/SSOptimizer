package github.kasuminova.ssoptimizer.common.input.ime;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Windows IMM32 输入法后端（待实现）。
 * <p>
 * 当前先接入 JNI 桥接与生命周期骨架，为后续 Win32 IMM API 实现预留稳定接缝。
 */
public final class WindowsImmImeBackend implements ImeBackend {
    private final WindowsImmNativeBridge bridge;
    private final BooleanSupplier        nativeLoadedSupplier;
    private final BooleanSupplier        windowsSupplier;

    private volatile long   contextHandle;
    private volatile String lastAttachFailureReason = "";

    public WindowsImmImeBackend() {
        this(WindowsImmNative.bridge(), NativeRuntime::isLoaded, WindowsImmImeBackend::isWindows);
    }

    WindowsImmImeBackend(final WindowsImmNativeBridge bridge) {
        this(bridge, () -> true, WindowsImmImeBackend::isWindows);
    }

    WindowsImmImeBackend(final WindowsImmNativeBridge bridge,
                         final BooleanSupplier nativeLoadedSupplier,
                         final BooleanSupplier windowsSupplier) {
        this.bridge = bridge;
        this.nativeLoadedSupplier = nativeLoadedSupplier;
        this.windowsSupplier = windowsSupplier;
    }

    private static boolean isWindows() {
        final String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("windows");
    }

    private static String normalizeFailureReason(final String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.trim();
    }

    @Override
    public boolean isAvailable() {
        return windowsSupplier.getAsBoolean() && nativeLoadedSupplier.getAsBoolean() && bridge.nativeIsSupported();
    }

    @Override
    public void attach(final long display, final long window) {
        if (!isAvailable()) {
            lastAttachFailureReason = "backend-unavailable";
            ImeDiagnostics.logLifecycle("attach-skipped", this, contextHandle, "reason=backend-unavailable");
            return;
        }

        detach();
        contextHandle = bridge.nativeCreateContext(display, window);
        if (contextHandle != 0L) {
            lastAttachFailureReason = "";
            final String nativeDebugSummary = normalizeFailureReason(bridge.nativeDebugSummary(contextHandle));
            ImeDiagnostics.logLifecycle("attached", this, contextHandle,
                    "display=0x" + Long.toHexString(display)
                            + " window=0x" + Long.toHexString(window)
                            + " native=" + nativeDebugSummary);
        } else {
            lastAttachFailureReason = normalizeFailureReason(bridge.nativeLastErrorMessage());
            ImeDiagnostics.logLifecycle("attach-failed", this, 0L,
                    "display=0x" + Long.toHexString(display)
                            + " window=0x" + Long.toHexString(window)
                            + " reason=" + lastAttachFailureReason);
        }
    }

    boolean hasActiveContext() {
        return contextHandle != 0L;
    }

    @Override
    public void detach() {
        final long handle = contextHandle;
        contextHandle = 0L;
        if (handle != 0L) {
            bridge.nativeDestroyContext(handle);
            ImeDiagnostics.logLifecycle("detached", this, handle, null);
        }
    }

    @Override
    public void focusIn() {
        final long handle = contextHandle;
        if (handle != 0L) {
            bridge.nativeFocusIn(handle);
            ImeDiagnostics.logLifecycle("enabled", this, handle, "reason=focus-gained");
        } else {
            ImeDiagnostics.logLifecycle("enable-skipped", this, 0L,
                    "reason=no-active-context attachFailure=" + normalizeFailureReason(lastAttachFailureReason));
        }
    }

    @Override
    public void focusOut() {
        final long handle = contextHandle;
        if (handle != 0L) {
            bridge.nativeFocusOut(handle);
            ImeDiagnostics.logLifecycle("disabled", this, handle, "reason=focus-lost");
        } else {
            ImeDiagnostics.logLifecycle("disable-skipped", this, 0L,
                    "reason=no-active-context attachFailure=" + normalizeFailureReason(lastAttachFailureReason));
        }
    }

    @Override
    public void updateSpot(final ImeCaretRect rect) {
        final long handle = contextHandle;
        if (handle == 0L || rect == null) {
            return;
        }
        bridge.nativeUpdateSpot(handle, rect.x(), rect.y(), rect.height());
    }

    @Override
    public boolean onX11KeyEvent(final long keyEventAddress, final int eventType) {
        return false;
    }

    @Override
    public String lastKeyEventSummary() {
        return "";
    }

    @Override
    public String pollCommittedText() {
        final long handle = contextHandle;
        if (handle == 0L) {
            return null;
        }
        return bridge.nativePollCommittedText(handle);
    }

    @Override
    public String currentPreeditText() {
        final long handle = contextHandle;
        if (handle == 0L) {
            return "";
        }
        final String preedit = bridge.nativeCurrentPreeditText(handle);
        return preedit != null ? preedit : "";
    }

    @Override
    public boolean isComposing() {
        final long handle = contextHandle;
        return handle != 0L && bridge.nativeIsComposing(handle);
    }

    @Override
    public boolean filterXimProtocolEvent(final long eventAddress) {
        return false;
    }

    String lastAttachFailureReason() {
        return lastAttachFailureReason;
    }
}