package github.kasuminova.ssoptimizer.common.input.ime;

/**
 * 空操作 IME 后端，在不支持输入法的平台或 IME 被禁用时使用。
 */
public final class NoopImeBackend implements ImeBackend {
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