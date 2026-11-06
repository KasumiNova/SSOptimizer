package github.kasuminova.ssoptimizer.common.input.ime;

public interface LinuxXimNativeBridge {
    boolean nativeIsSupported();

    String nativeLastErrorMessage();

    String nativeDebugSummary(long contextHandle);

    long nativeCreateContext(long display, long window, int style);

    void nativeDestroyContext(long contextHandle);

    void nativeFocusIn(long contextHandle);

    void nativeFocusOut(long contextHandle);

    void nativeUpdateSpot(long contextHandle, int x, int y, int height);

    boolean nativeHandleKeyEvent(long contextHandle, long keyEventAddress, int eventType);

    String nativeLastKeyEventSummary(long contextHandle);

    String nativePollCommittedText(long contextHandle);

    String nativeCurrentPreeditText(long contextHandle);

    boolean nativeIsComposing(long contextHandle);

    boolean nativeFilterXimProtocolEvent(long contextHandle, long eventAddress);
}