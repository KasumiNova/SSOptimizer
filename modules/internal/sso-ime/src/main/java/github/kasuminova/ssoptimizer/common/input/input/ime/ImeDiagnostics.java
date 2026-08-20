package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import org.apache.log4j.Logger;

import java.util.Locale;

/**
 * IME 诊断日志工具。
 * <p>
 * 集中管理输入法相关的结构化日志输出，
 * 含后端选择、事件处理、焦点变化等诊断信息。
 */
final class ImeDiagnostics {
    private static final Logger LOGGER                  = Logger.getLogger(ImeDiagnostics.class);
    private static final int    MAX_TEXT_PREVIEW_LENGTH = 32;

    private ImeDiagnostics() {
    }

    static void logBackendSelection(final ImeProperties.BackendMode mode,
                                    final boolean enabled,
                                    final ImeBackend backend) {
        if (!ImeProperties.diagnosticsEnabled()) {
            return;
        }

        final String osName = System.getProperty("os.name", "");
        final String backendName = backend != null ? backend.getClass().getSimpleName() : "null";
        final boolean available = backend != null && backend.isAvailable();
        LOGGER.info(String.format(Locale.ROOT,
                "[SSOptimizer] IME backend selection: enabled=%s mode=%s backend=%s available=%s os=%s",
                enabled,
                mode,
                backendName,
                available,
                osName));
    }

    static void logLifecycle(final String event,
                             final ImeBackend backend,
                             final long contextHandle,
                             final String detail) {
        final String backendName = backend != null ? backend.getClass().getSimpleName() : "null";
        final String suffix = detail == null || detail.isBlank() ? "" : " " + detail;
        final String message = String.format(Locale.ROOT,
                "[SSOptimizer] IME %s backend=%s context=%s%s",
                event,
                backendName,
                hex(contextHandle),
                suffix);
        if (ImeProperties.diagnosticsEnabled()) {
            LOGGER.info(message);
            return;
        }
        if ("attach-failed".equals(event)) {
            LOGGER.warn(message);
        }
    }

    static void logCommittedText(final String committed) {
        if (!ImeProperties.diagnosticsEnabled() || committed == null || committed.isEmpty()) {
            return;
        }

        LOGGER.info(String.format(Locale.ROOT,
                "[SSOptimizer] IME committed text length=%d preview=%s",
                committed.length(),
                preview(committed)));
    }

    static void logPreeditState(final int eventType,
                                final boolean composing,
                                final String preeditText) {
        if (!ImeProperties.diagnosticsEnabled()) {
            return;
        }

        if (!composing && (preeditText == null || preeditText.isBlank())) {
            return;
        }

        LOGGER.info(String.format(Locale.ROOT,
                "[SSOptimizer] IME preedit eventType=%d composing=%s preedit=%s",
                eventType,
                composing,
                preview(preeditText == null ? "" : preeditText)));
    }

    static void logTextFieldRegistration(final TextFieldAPI textField,
                                         final int registeredCount) {
        if (!ImeProperties.diagnosticsEnabled() || textField == null) {
            return;
        }

        final String text = textField.getText() != null ? textField.getText() : "";
        LOGGER.info(String.format(Locale.ROOT,
                "[SSOptimizer] IME registered text field id=%s registered=%d textLength=%d focused=%s",
                Integer.toHexString(System.identityHashCode(textField)),
                registeredCount,
                text.length(),
                textField.hasFocus()));
    }

    static void logTextFieldFocus(final String event,
                                  final TextFieldAPI textField,
                                  final int registeredCount) {
        if (!ImeProperties.diagnosticsEnabled() || textField == null) {
            return;
        }

        final String text = textField.getText() != null ? textField.getText() : "";
        LOGGER.info(String.format(Locale.ROOT,
                "[SSOptimizer] IME text field %s id=%s registered=%d textLength=%d focused=%s",
                event,
                Integer.toHexString(System.identityHashCode(textField)),
                registeredCount,
                text.length(),
                textField.hasFocus()));
    }

    static void logFocuslessComposition(final int registeredCount,
                                        final boolean composing,
                                        final String preeditText) {
        if (!ImeProperties.diagnosticsEnabled()) {
            return;
        }

        if (!composing && (preeditText == null || preeditText.isBlank())) {
            return;
        }

        LOGGER.info(String.format(Locale.ROOT,
                "[SSOptimizer] IME composition has no focused text field registered=%d composing=%s preedit=%s",
                registeredCount,
                composing,
                preview(preeditText == null ? "" : preeditText)));
    }

    static void logX11KeyEvent(final int eventType,
                               final long displayWindow,
                               final long eventWindow,
                               final boolean filtered,
                               final boolean consumed) {
        if (!ImeProperties.diagnosticsEnabled()) {
            return;
        }

        LOGGER.info(String.format(Locale.ROOT,
                "[SSOptimizer] IME X11 key event type=%d displayWindow=%s eventWindow=%s filtered=%s consumed=%s",
                eventType,
                hex(displayWindow),
                hex(eventWindow),
                filtered,
                consumed));
    }

    private static String preview(final String text) {
        final String normalized = text
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        if (normalized.length() <= MAX_TEXT_PREVIEW_LENGTH) {
            return '"' + normalized + '"';
        }
        return '"' + normalized.substring(0, MAX_TEXT_PREVIEW_LENGTH) + "…\"";
    }

    private static String hex(final long value) {
        return "0x" + Long.toHexString(value);
    }
}