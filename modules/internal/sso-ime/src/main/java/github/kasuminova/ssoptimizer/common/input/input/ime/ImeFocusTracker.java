package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.ui.FocusedComponentTracker;
import org.apache.log4j.Logger;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

final class ImeFocusTracker {
    private static final Logger LOGGER = Logger.getLogger(ImeFocusTracker.class);

    private final CopyOnWriteArrayList<WeakReference<TextFieldAPI>> registeredFields = new CopyOnWriteArrayList<>();
    private final ImplicitFocusListener                             implicitFocusListener;
    private volatile WeakReference<TextFieldAPI>                    explicitlyFocusedField;

    ImeFocusTracker(final ImplicitFocusListener implicitFocusListener) {
        this.implicitFocusListener = implicitFocusListener;
    }

    void register(final TextFieldAPI textField) {
        ensureRegistered(textField);
        if (textField.hasFocus()) {
            syncImplicitFocusedField(textField);
        }
    }

    void onFocusGained(final TextFieldAPI textField) {
        ensureRegistered(textField);
        explicitlyFocusedField = new WeakReference<>(textField);
    }

    void onFocusLost(final TextFieldAPI textField) {
        final TextFieldAPI current = explicitlyFocusedField != null ? explicitlyFocusedField.get() : null;
        if (current == textField) {
            explicitlyFocusedField = null;
        }
    }

    TextFieldAPI currentFocusedField() {
        final TextFieldAPI explicit = explicitlyFocusedField != null ? explicitlyFocusedField.get() : null;
        if (explicit == null) {
            explicitlyFocusedField = null;
        } else if (explicit.hasFocus()) {
            return explicit;
        }

        final TextFieldAPI globallyFocused = globalFocusedTextField();
        if (globallyFocused != null && globallyFocused != explicit && globallyFocused.hasFocus()) {
            ensureRegistered(globallyFocused);
            syncImplicitFocusedField(globallyFocused);
            return globallyFocused;
        }

        final TextFieldAPI registeredFocused = focusedRegisteredField(explicit);
        if (registeredFocused != null) {
            syncImplicitFocusedField(registeredFocused);
            return registeredFocused;
        }

        return explicit;
    }

    void cleanupStaleReferences() {
        for (Iterator<WeakReference<TextFieldAPI>> it = registeredFields.iterator(); it.hasNext(); ) {
            final WeakReference<TextFieldAPI> ref = it.next();
            if (ref.get() == null) {
                registeredFields.remove(ref);
            }
        }
    }

    int registeredFieldCount() {
        return registeredFields.size();
    }

    private TextFieldAPI focusedRegisteredField(final TextFieldAPI excluded) {
        for (WeakReference<TextFieldAPI> ref : registeredFields) {
            final TextFieldAPI field = ref.get();
            if (field != null && field != excluded && field.hasFocus()) {
                return field;
            }
        }
        return null;
    }

    private void ensureRegistered(final TextFieldAPI textField) {
        cleanupStaleReferences();
        for (WeakReference<TextFieldAPI> ref : registeredFields) {
            if (ref.get() == textField) {
                return;
            }
        }
        registeredFields.add(new WeakReference<>(textField));
        ImeDiagnostics.logTextFieldRegistration(textField, registeredFieldCount());
    }

    private void syncImplicitFocusedField(final TextFieldAPI textField) {
        if (textField == null || !textField.hasFocus()) {
            return;
        }

        final TextFieldAPI explicit = explicitlyFocusedField != null ? explicitlyFocusedField.get() : null;
        if (explicit == textField) {
            return;
        }

        explicitlyFocusedField = new WeakReference<>(textField);
        implicitFocusListener.onImplicitFocus(textField);
    }

    private TextFieldAPI globalFocusedTextField() {
        try {
            final Object focused = FocusedComponentTracker.getCurrentFocusedComponent();
            return focused instanceof TextFieldAPI textField ? textField : null;
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Failed to read the globally focused component.", e);
            return null;
        }
    }

    interface ImplicitFocusListener {
        void onImplicitFocus(TextFieldAPI textField);
    }
}
