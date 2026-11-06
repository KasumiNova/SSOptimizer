package github.kasuminova.ssoptimizer.common.input.ime;

public interface WindowsImmNativeBridge {
    boolean nativeIsSupported();

    String nativeLastErrorMessage();

    String nativeDebugSummary(long contextHandle);

    long nativeCreateContext(long display, long window);

    void nativeDestroyContext(long contextHandle);

    void nativeFocusIn(long contextHandle);

    void nativeFocusOut(long contextHandle);

    void nativeUpdateSpot(long contextHandle, int x, int y, int height);

    String nativePollCommittedText(long contextHandle);

    String nativeCurrentPreeditText(long contextHandle);

    boolean nativeIsComposing(long contextHandle);
}