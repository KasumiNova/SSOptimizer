package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;

/**
 * 文本框 IME 钩子（由 Mixin 注入调用）。
 * <p>
 * 在文本框创建、获得/失去焦点时通知 {@link ImeService}，
 * 让输入法后端正确跟踪当前活动的文本框。
 */
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