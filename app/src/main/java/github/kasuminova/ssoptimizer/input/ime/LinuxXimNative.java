package github.kasuminova.ssoptimizer.input.ime;

public final class LinuxXimNative {
    private static final LinuxXimNativeBridge BRIDGE = new LinuxXimNativeBridge() {
        @Override
        public boolean nativeIsSupported() {
            return LinuxXimNative.nativeIsSupported();
        }

        @Override
        public String nativeLastErrorMessage() {
            return LinuxXimNative.nativeLastErrorMessage();
        }

        @Override
        public String nativeDebugSummary(final long contextHandle) {
            return LinuxXimNative.nativeDebugSummary(contextHandle);
        }

        @Override
        public long nativeCreateContext(final long display, final long window, final int style) {
            return LinuxXimNative.nativeCreateContext(display, window, style);
        }

        @Override
        public void nativeDestroyContext(final long contextHandle) {
            LinuxXimNative.nativeDestroyContext(contextHandle);
        }

        @Override
        public void nativeFocusIn(final long contextHandle) {
            LinuxXimNative.nativeFocusIn(contextHandle);
        }

        @Override
        public void nativeFocusOut(final long contextHandle) {
            LinuxXimNative.nativeFocusOut(contextHandle);
        }

        @Override
        public void nativeUpdateSpot(final long contextHandle, final int x, final int y, final int height) {
            LinuxXimNative.nativeUpdateSpot(contextHandle, x, y, height);
        }

        @Override
        public boolean nativeHandleKeyEvent(final long contextHandle, final long keyEventAddress, final int eventType) {
            return LinuxXimNative.nativeHandleKeyEvent(contextHandle, keyEventAddress, eventType);
        }

        @Override
        public String nativeLastKeyEventSummary(final long contextHandle) {
            return LinuxXimNative.nativeLastKeyEventSummary(contextHandle);
        }

        @Override
        public String nativePollCommittedText(final long contextHandle) {
            return LinuxXimNative.nativePollCommittedText(contextHandle);
        }

        @Override
        public String nativeCurrentPreeditText(final long contextHandle) {
            return LinuxXimNative.nativeCurrentPreeditText(contextHandle);
        }

        @Override
        public boolean nativeIsComposing(final long contextHandle) {
            return LinuxXimNative.nativeIsComposing(contextHandle);
        }

        @Override
        public boolean nativeFilterXimProtocolEvent(final long contextHandle, final long eventAddress) {
            return LinuxXimNative.nativeFilterXimProtocolEvent(contextHandle, eventAddress);
        }
    };

    private LinuxXimNative() {
    }

    public static LinuxXimNativeBridge bridge() {
        return BRIDGE;
    }

    public static native boolean nativeIsSupported();

    public static native String nativeLastErrorMessage();

    public static native String nativeDebugSummary(long handle);

    public static native long nativeCreateContext(long display, long window, int style);

    public static native void nativeDestroyContext(long handle);

    public static native void nativeFocusIn(long handle);

    public static native void nativeFocusOut(long handle);

    public static native void nativeUpdateSpot(long handle, int x, int y, int height);

    public static native boolean nativeHandleKeyEvent(long handle, long keyEventAddress, int eventType);

    public static native String nativePollCommittedText(long handle);

    public static native String nativeLastKeyEventSummary(long contextHandle);

    public static native String nativeCurrentPreeditText(long handle);

    public static native boolean nativeIsComposing(long handle);

    public static native boolean nativeFilterXimProtocolEvent(long handle, long eventAddress);
}