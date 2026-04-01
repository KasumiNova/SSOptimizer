package github.kasuminova.ssoptimizer.common.input.ime;

/**
 * Windows IMM32 输入法后端（待实现）。
 * <p>
 * 预留接口，计划通过 Win32 IMM API 实现 Windows 平台中文输入支持。
 */
public final class WindowsImmImeBackend implements ImeBackend {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void attach(final long display, final long window) {
    }

    @Override
    public void detach() {
    }

    @Override
    public void focusIn() {
    }

    @Override
    public void focusOut() {
    }

    @Override
    public void updateSpot(final ImeCaretRect rect) {
    }

    @Override
    public boolean onX11KeyEvent(final long keyEventAddress, final int eventType) {
        return false;
    }

    @Override
    public String lastKeyEventSummary() {
        return "";
    }

    @Override
    public String pollCommittedText() {
        return null;
    }

    @Override
    public String currentPreeditText() {
        return "";
    }

    @Override
    public boolean isComposing() {
        return false;
    }

    @Override
    public boolean filterXimProtocolEvent(final long eventAddress) {
        return false;
    }
}