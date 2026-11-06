package github.kasuminova.ssoptimizer.common.input.ime;

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