package github.kasuminova.ssoptimizer.common.input.ime;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Linux XIM 协议输入法后端实现。
 * <p>
 * 通过 JNI 调用原生代码创建 X11 Input Method / Input Context，
 * 处理 {@code XFilterEvent} + {@code Xutf8LookupString} 的完整链路。
 */
public final class LinuxXimImeBackend implements ImeBackend {
    private static final int DEFAULT_INPUT_STYLE = 0;

    private final LinuxXimNativeBridge bridge;
    private final BooleanSupplier nativeLoadedSupplier;

    private volatile long contextHandle;
    private volatile String lastAttachFailureReason = "";

    public LinuxXimImeBackend() {
        this(LinuxXimNative.bridge(), NativeRuntime::isLoaded);
    }

    LinuxXimImeBackend(final LinuxXimNativeBridge bridge) {
        this(bridge, () -> true);
    }

    LinuxXimImeBackend(final LinuxXimNativeBridge bridge,
                       final BooleanSupplier nativeLoadedSupplier) {
        this.bridge = bridge;
        this.nativeLoadedSupplier = nativeLoadedSupplier;
    }

    @Override
    public boolean isAvailable() {
        return isLinux() && nativeLoadedSupplier.getAsBoolean() && bridge.nativeIsSupported();
    }

    @Override
    public void attach(final long display, final long window) {
        if (!isAvailable()) {
            lastAttachFailureReason = "backend-unavailable";
            ImeDiagnostics.logLifecycle("attach-skipped", this, contextHandle, "reason=backend-unavailable");
            return;
        }
        detach();
        contextHandle = bridge.nativeCreateContext(display, window, DEFAULT_INPUT_STYLE);
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
        final long handle = contextHandle;
        if (handle != 0L) {
            return bridge.nativeHandleKeyEvent(handle, keyEventAddress, eventType);
        }
        return false;
    }

    @Override
    public String lastKeyEventSummary() {
        final long handle = contextHandle;
        if (handle == 0L) {
            return "";
        }
        final String summary = bridge.nativeLastKeyEventSummary(handle);
        return summary != null ? summary : "";
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
        return bridge.nativeCurrentPreeditText(handle);
    }

    @Override
    public boolean isComposing() {
        final long handle = contextHandle;
        return handle != 0L && bridge.nativeIsComposing(handle);
    }

    @Override
    public boolean filterXimProtocolEvent(final long eventAddress) {
        final long handle = contextHandle;
        return handle != 0L && bridge.nativeFilterXimProtocolEvent(handle, eventAddress);
    }

    String lastAttachFailureReason() {
        return lastAttachFailureReason;
    }

    private static String normalizeFailureReason(final String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.trim();
    }

    private static boolean isLinux() {
        final String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("linux");
    }
}