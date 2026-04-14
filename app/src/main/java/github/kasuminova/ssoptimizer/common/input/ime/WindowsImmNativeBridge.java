package github.kasuminova.ssoptimizer.common.input.ime;

/**
 * Windows IMM32 原生方法桥接接口。
 * <p>
 * 抽象出 JNI 调用契约，便于在单元测试中使用 mock 实现替代真实原生库。
 */
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