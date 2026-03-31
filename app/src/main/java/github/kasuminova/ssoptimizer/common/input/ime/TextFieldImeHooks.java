package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;

public final class TextFieldImeHooks {
    private TextFieldImeHooks() {
    }

    public static void registerCreatedTextField(final TextFieldAPI textField) {
        if (textField != null) {
            ImeService.getInstance().register(textField);
        }
    }

    public static void onTextFieldFocusGained(final TextFieldAPI textField) {
        if (textField != null) {
            ImeService.getInstance().onFocusGained(textField);
        }
    }

    public static void onTextFieldFocusLost(final TextFieldAPI textField) {
        if (textField != null) {
            ImeService.getInstance().onFocusLost(textField);
        }
    }
}