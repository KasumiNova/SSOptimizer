package github.kasuminova.ssoptimizer.common.input.ime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxXimImeBackendTest {
    @Test
    void reportsUnavailableWhenNativeBridgeIsMissing() {
        LinuxXimImeBackend backend = new LinuxXimImeBackend(new FakeBridge(false));
        assertFalse(backend.isAvailable());
    }

    @Test
    void recordsAttachFailureReasonFromNativeBridge() {
        FakeBridge bridge = new FakeBridge(true);
        bridge.handle = 0L;
        bridge.lastError = "XOpenIM returned null";

        LinuxXimImeBackend backend = new LinuxXimImeBackend(bridge);
        backend.attach(11L, 22L);

        assertEquals("XOpenIM returned null", backend.lastAttachFailureReason());
    }

    @Test
    void forwardsAttachFocusSpotAndCommitToNativeBridge() {
        FakeBridge bridge = new FakeBridge(true);
        LinuxXimImeBackend backend = new LinuxXimImeBackend(bridge);

        backend.attach(11L, 22L);
        backend.focusIn();
        backend.updateSpot(new ImeCaretRect(100, 200, 24));
        bridge.handleKeyEventResult = true;
        bridge.commit = "中文";

        assertTrue(backend.onX11KeyEvent(77L, 2));
        assertEquals("中文", backend.pollCommittedText());
        assertTrue(bridge.attached);
        assertTrue(bridge.focused);
        assertEquals(100, bridge.lastSpotX);
        assertEquals(200, bridge.lastSpotY);
        assertEquals(24, bridge.lastSpotHeight);
    }

    private static final class FakeBridge implements LinuxXimNativeBridge {
        boolean supported;
        boolean attached;
        boolean focused;
        int lastSpotX;
        int lastSpotY;
        int lastSpotHeight;
        long handle = 1L;
        String lastError = "";
        String commit;
        boolean handleKeyEventResult;

        private FakeBridge(boolean supported) {
            this.supported = supported;
        }

        @Override public boolean nativeIsSupported() { return supported; }
        @Override public String nativeLastErrorMessage() { return lastError; }
        @Override public String nativeDebugSummary(long contextHandle) { return "requestedModifiers=@im=fcitx appliedModifiers=@im=fcitx"; }
        @Override public long nativeCreateContext(long display, long window, int style) { attached = true; return handle; }
        @Override public void nativeDestroyContext(long contextHandle) { attached = false; }
        @Override public void nativeFocusIn(long contextHandle) { focused = true; }
        @Override public void nativeFocusOut(long contextHandle) { focused = false; }
        @Override public void nativeUpdateSpot(long contextHandle, int x, int y, int height) { lastSpotX = x; lastSpotY = y; lastSpotHeight = height; }
        @Override public boolean nativeHandleKeyEvent(long contextHandle, long keyEventAddress, int eventType) { return handleKeyEventResult; }
        @Override public String nativeLastKeyEventSummary(long contextHandle) { return "keycode=0 state=0x0 keysym=0x0 status=0 length=0 committed=\"\""; }
        @Override public String nativePollCommittedText(long contextHandle) { String v = commit; commit = null; return v; }
        @Override public String nativeCurrentPreeditText(long contextHandle) { return ""; }
        @Override public boolean nativeIsComposing(long contextHandle) { return false; }
        @Override public boolean nativeFilterXimProtocolEvent(long contextHandle, long eventAddress) { return false; }
    }
}
