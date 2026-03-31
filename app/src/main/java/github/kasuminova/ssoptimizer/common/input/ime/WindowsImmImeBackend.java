package github.kasuminova.ssoptimizer.common.input.ime;

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