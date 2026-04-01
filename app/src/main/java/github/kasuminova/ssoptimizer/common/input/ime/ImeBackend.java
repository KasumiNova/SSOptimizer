package github.kasuminova.ssoptimizer.common.input.ime;

/**
 * 输入法后端抽象接口。
 * <p>
 * 定义平台无关的 IME 生命周期（attach/detach/focus）和事件处理契约。
 * 不同平台提供各自实现：Linux 使用 XIM 协议，Windows 使用 IMM32 API。
 */
public interface ImeBackend {
    boolean isAvailable();

    void attach(long display, long window);

    void detach();

    void focusIn();

    void focusOut();

    void updateSpot(ImeCaretRect rect);

    boolean onX11KeyEvent(long keyEventAddress, int eventType);

    String lastKeyEventSummary();

    String pollCommittedText();

    String currentPreeditText();

    boolean isComposing();

    boolean filterXimProtocolEvent(long eventAddress);
}