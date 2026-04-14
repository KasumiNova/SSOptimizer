package github.kasuminova.ssoptimizer.common.input.ime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowsImmImeBackendTest {
    @Test
    void reportsUnavailableWhenNativeBridgeIsMissing() {
        final WindowsImmImeBackend backend = new WindowsImmImeBackend(new FakeBridge(false), () -> true, () -> true);
        assertFalse(backend.isAvailable());
    }

    @Test
    void recordsAttachFailureReasonFromNativeBridge() {
        final FakeBridge bridge = new FakeBridge(true);
        bridge.handle = 0L;
        bridge.lastError = "ImmGetContext returned null";

        final WindowsImmImeBackend backend = new WindowsImmImeBackend(bridge, () -> true, () -> true);
        backend.attach(11L, 22L);

        assertEquals("ImmGetContext returned null", backend.lastAttachFailureReason());
    }

    @Test
    void forwardsAttachFocusSpotAndCommitToNativeBridge() {
        final FakeBridge bridge = new FakeBridge(true);
        final WindowsImmImeBackend backend = new WindowsImmImeBackend(bridge, () -> true, () -> true);

        backend.attach(11L, 22L);
        backend.focusIn();
        backend.updateSpot(new ImeCaretRect(100, 200, 24));
        bridge.commit = "中文";

        assertEquals("中文", backend.pollCommittedText());
        assertTrue(bridge.attached);
        assertTrue(bridge.focused);
        assertEquals(100, bridge.lastSpotX);
        assertEquals(200, bridge.lastSpotY);
        assertEquals(24, bridge.lastSpotHeight);
        assertTrue(backend.hasActiveContext());
    }

    @Test
    void reportsAvailableWhenRunningOnWindowsWithNativeStubEnabled() {
        final FakeBridge bridge = new FakeBridge(true);
        final WindowsImmImeBackend backend = new WindowsImmImeBackend(bridge, () -> true, () -> true);

        assertTrue(backend.isAvailable());
    }

    @Test
    void focusStateTracksNativeBridgeState() {
        final FakeBridge bridge = new FakeBridge(true);
        final WindowsImmImeBackend backend = new WindowsImmImeBackend(bridge, () -> true, () -> true);

        backend.attach(11L, 22L);
        backend.focusIn();
        backend.focusOut();

        assertFalse(bridge.focused);
        assertEquals(1, bridge.focusInCount);
        assertEquals(1, bridge.focusOutCount);
    }

    @Test
    void exposesPreeditAndComposingStateFromNativeBridge() {
        final FakeBridge bridge = new FakeBridge(true);
        bridge.preedit = "zhong";
        bridge.composing = true;

        final WindowsImmImeBackend backend = new WindowsImmImeBackend(bridge, () -> true, () -> true);
        backend.attach(11L, 22L);

        assertEquals("zhong", backend.currentPreeditText());
        assertTrue(backend.isComposing());
    }

    private static final class FakeBridge implements WindowsImmNativeBridge {
        boolean supported;
        boolean attached;
        boolean focused;
        int     focusInCount;
        int     focusOutCount;
        int     lastSpotX;
        int     lastSpotY;
        int     lastSpotHeight;
        long    handle    = 1L;
        String  lastError = "";
        String  commit;
        String  preedit = "";
        boolean composing;

        private FakeBridge(final boolean supported) {
            this.supported = supported;
        }

        @Override
        public boolean nativeIsSupported() {
            return supported;
        }

        @Override
        public String nativeLastErrorMessage() {
            return lastError;
        }

        @Override
        public String nativeDebugSummary(final long contextHandle) {
            return "imm32-stub";
        }

        @Override
        public long nativeCreateContext(final long display, final long window) {
            attached = true;
            return handle;
        }

        @Override
        public void nativeDestroyContext(final long contextHandle) {
            attached = false;
        }

        @Override
        public void nativeFocusIn(final long contextHandle) {
            focused = true;
            focusInCount++;
        }

        @Override
        public void nativeFocusOut(final long contextHandle) {
            focused = false;
            focusOutCount++;
        }

        @Override
        public void nativeUpdateSpot(final long contextHandle, final int x, final int y, final int height) {
            lastSpotX = x;
            lastSpotY = y;
            lastSpotHeight = height;
        }

        @Override
        public String nativePollCommittedText(final long contextHandle) {
            final String value = commit;
            commit = null;
            return value;
        }

        @Override
        public String nativeCurrentPreeditText(final long contextHandle) {
            return preedit;
        }

        @Override
        public boolean nativeIsComposing(final long contextHandle) {
            return composing;
        }
    }
}