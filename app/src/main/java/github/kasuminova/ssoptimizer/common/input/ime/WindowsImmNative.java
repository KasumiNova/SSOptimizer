package github.kasuminova.ssoptimizer.common.input.ime;

/**
 * Windows IMM32 原生 JNI 方法定义与桥接实例工厂。
 * <p>
 * 封装所有 {@code native} 方法并提供统一的 {@link WindowsImmNativeBridge}
 * 实例，供 {@link WindowsImmImeBackend} 调用。
 */
public final class WindowsImmNative {
    private static final WindowsImmNativeBridge BRIDGE = new WindowsImmNativeBridge() {
        @Override
        public boolean nativeIsSupported() {
            return WindowsImmNative.nativeIsSupported();
        }

        @Override
        public String nativeLastErrorMessage() {
            return WindowsImmNative.nativeLastErrorMessage();
        }

        @Override
        public String nativeDebugSummary(final long contextHandle) {
            return WindowsImmNative.nativeDebugSummary(contextHandle);
        }

        @Override
        public long nativeCreateContext(final long display, final long window) {
            return WindowsImmNative.nativeCreateContext(display, window);
        }

        @Override
        public void nativeDestroyContext(final long contextHandle) {
            WindowsImmNative.nativeDestroyContext(contextHandle);
        }

        @Override
        public void nativeFocusIn(final long contextHandle) {
            WindowsImmNative.nativeFocusIn(contextHandle);
        }

        @Override
        public void nativeFocusOut(final long contextHandle) {
            WindowsImmNative.nativeFocusOut(contextHandle);
        }

        @Override
        public void nativeUpdateSpot(final long contextHandle, final int x, final int y, final int height) {
            WindowsImmNative.nativeUpdateSpot(contextHandle, x, y, height);
        }

        @Override
        public String nativePollCommittedText(final long contextHandle) {
            return WindowsImmNative.nativePollCommittedText(contextHandle);
        }

        @Override
        public String nativeCurrentPreeditText(final long contextHandle) {
            return WindowsImmNative.nativeCurrentPreeditText(contextHandle);
        }

        @Override
        public boolean nativeIsComposing(final long contextHandle) {
            return WindowsImmNative.nativeIsComposing(contextHandle);
        }
    };

    private WindowsImmNative() {
    }

    public static WindowsImmNativeBridge bridge() {
        return BRIDGE;
    }

    public static native boolean nativeIsSupported();

    public static native String nativeLastErrorMessage();

    public static native String nativeDebugSummary(long handle);

    public static native long nativeCreateContext(long display, long window);

    public static native void nativeDestroyContext(long handle);

    public static native void nativeFocusIn(long handle);

    public static native void nativeFocusOut(long handle);

    public static native void nativeUpdateSpot(long handle, int x, int y, int height);

    public static native String nativePollCommittedText(long handle);

    public static native String nativeCurrentPreeditText(long handle);

    public static native boolean nativeIsComposing(long handle);
}