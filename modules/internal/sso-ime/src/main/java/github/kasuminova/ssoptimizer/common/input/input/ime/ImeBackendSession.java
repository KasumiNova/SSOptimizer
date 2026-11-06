package github.kasuminova.ssoptimizer.common.input.ime;

final class ImeBackendSession {
    private volatile ImeBackend backend;
    private volatile boolean    windowFocused = true;

    ImeBackendSession(final ImeBackend backend) {
        this.backend = backend != null ? backend : new NoopImeBackend();
    }

    ImeBackend backend() {
        return backend;
    }

    boolean isWindowFocused() {
        return windowFocused;
    }

    void setWindowFocused(final boolean windowFocused) {
        this.windowFocused = windowFocused;
    }

    void focusIn() {
        if (windowFocused) {
            backend.focusIn();
        }
    }

    void focusOut() {
        backend.focusOut();
    }

    void updateSpot(final ImeCaretRect rect) {
        if (windowFocused && rect != null) {
            backend.updateSpot(rect);
        }
    }
}
