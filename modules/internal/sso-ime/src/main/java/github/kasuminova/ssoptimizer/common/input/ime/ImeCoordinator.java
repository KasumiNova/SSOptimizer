package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import github.kasuminova.ssoptimizer.common.font.EffectiveScreenScale;
import org.lwjgl.opengl.Display;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

public final class ImeCoordinator {
    private static final ImeCoordinator INSTANCE = new ImeCoordinator(
            ImeBackends.createDefault(),
            EffectiveScreenScale::current,
            () -> Display.getHeight());

    private final ImeBackendSession session;
    private final ImeFocusTracker   focusTracker;
    private final ImeCaretLocator   caretLocator;
    private final ImeTextCommitter  textCommitter;

    public ImeCoordinator(final ImeBackend backend) {
        this(backend, EffectiveScreenScale::current, () -> Display.getHeight());
    }

    ImeCoordinator(final ImeBackend backend,
                   final DoubleSupplier windowScaleSupplier,
                   final IntSupplier windowHeightSupplier) {
        session = new ImeBackendSession(backend);
        caretLocator = new ImeCaretLocator(windowScaleSupplier, windowHeightSupplier);
        focusTracker = new ImeFocusTracker(this::onImplicitFocus);
        textCommitter = new ImeTextCommitter(session);
    }

    public static ImeCoordinator getInstance() {
        return INSTANCE;
    }

    public ImeBackend backend() {
        return session.backend();
    }

    public void register(final TextFieldAPI textField) {
        if (textField != null) {
            focusTracker.register(textField);
        }
    }

    public void onFocusGained(final TextFieldAPI textField) {
        if (textField == null) {
            return;
        }
        focusTracker.onFocusGained(textField);
        WindowsDisplayImeHooks.ensureAttached(session.backend(), this);
        session.focusIn();
        final ImeCaretRect rect = caretLocator.locate(textField);
        session.updateSpot(rect);
        ImeDiagnostics.logTextFieldFocus("focused", textField, focusTracker.registeredFieldCount());
    }

    public void onFocusLost(final TextFieldAPI textField) {
        focusTracker.onFocusLost(textField);
        if (textField != null) {
            ImeDiagnostics.logTextFieldFocus("blurred", textField, focusTracker.registeredFieldCount());
        }

        final TextFieldAPI focused = focusTracker.currentFocusedField();
        if (focused == null || !session.isWindowFocused()) {
            session.focusOut();
            return;
        }

        session.updateSpot(computeCurrentCaretRect());
    }

    void onWindowFocusChanged(final boolean focused) {
        session.setWindowFocused(focused);
        if (!focused) {
            session.focusOut();
            return;
        }

        final TextFieldAPI currentFocusedField = focusTracker.currentFocusedField();
        if (currentFocusedField == null) {
            return;
        }

        WindowsDisplayImeHooks.ensureAttached(session.backend(), this);
        session.focusIn();
        session.updateSpot(computeCurrentCaretRect());
    }

    boolean shouldCaptureImeInput() {
        return session.isWindowFocused() && focusTracker.currentFocusedField() != null;
    }

    public ImeCaretRect computeCurrentCaretRect() {
        focusTracker.cleanupStaleReferences();
        return caretLocator.locate(focusTracker.currentFocusedField());
    }

    public void pollAndApplyCommittedText() {
        focusTracker.cleanupStaleReferences();
        final TextFieldAPI focused = focusTracker.currentFocusedField();
        textCommitter.pollAndApplyCommittedText(focused, focusTracker.registeredFieldCount());
    }

    private void onImplicitFocus(final TextFieldAPI textField) {
        if (session.isWindowFocused()) {
            WindowsDisplayImeHooks.ensureAttached(session.backend(), this);
            session.focusIn();
            session.updateSpot(caretLocator.locate(textField));
        }
        ImeDiagnostics.logTextFieldFocus("focused-implicit", textField, focusTracker.registeredFieldCount());
    }
}
