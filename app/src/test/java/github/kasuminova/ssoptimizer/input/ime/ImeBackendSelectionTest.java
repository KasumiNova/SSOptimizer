package github.kasuminova.ssoptimizer.input.ime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ImeBackendSelectionTest {
    @Test
    void selectsLinuxBackendWhenForced() {
        System.setProperty("ssoptimizer.ime.backend", "linux-xim");
        ImeBackend backend = ImeBackends.create(new LinuxXimImeBackend(new UnsupportedBridge()), new WindowsImmImeBackend());
        assertInstanceOf(LinuxXimImeBackend.class, backend);
    }

    @Test
    void fallsBackToNoopWhenDisabled() {
        System.setProperty("ssoptimizer.ime.backend", "none");
        ImeBackend backend = ImeBackends.create(new LinuxXimImeBackend(new UnsupportedBridge()), new WindowsImmImeBackend());
        assertInstanceOf(NoopImeBackend.class, backend);
    }

    private static final class UnsupportedBridge implements LinuxXimNativeBridge {
        @Override public boolean nativeIsSupported() { return false; }
        @Override public String nativeLastErrorMessage() { return "unsupported"; }
        @Override public String nativeDebugSummary(long contextHandle) { return "unsupported"; }
        @Override public long nativeCreateContext(long display, long window, int style) { return 0L; }
        @Override public void nativeDestroyContext(long contextHandle) {}
        @Override public void nativeFocusIn(long contextHandle) {}
        @Override public void nativeFocusOut(long contextHandle) {}
        @Override public void nativeUpdateSpot(long contextHandle, int x, int y, int height) {}
        @Override public boolean nativeHandleKeyEvent(long contextHandle, long keyEventAddress, int eventType) { return false; }
        @Override public String nativeLastKeyEventSummary(long contextHandle) { return ""; }
        @Override public String nativePollCommittedText(long contextHandle) { return null; }
        @Override public String nativeCurrentPreeditText(long contextHandle) { return ""; }
        @Override public boolean nativeIsComposing(long contextHandle) { return false; }
        @Override public boolean nativeFilterXimProtocolEvent(long contextHandle, long eventAddress) { return false; }
    }
}
