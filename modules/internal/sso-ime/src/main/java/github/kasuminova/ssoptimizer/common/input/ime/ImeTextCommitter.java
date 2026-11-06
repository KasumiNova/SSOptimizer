package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;

final class ImeTextCommitter {
    private final ImeBackendSession session;

    ImeTextCommitter(final ImeBackendSession session) {
        this.session = session;
    }

    void pollAndApplyCommittedText(final TextFieldAPI focused,
                                   final int registeredFieldCount) {
        if (focused == null) {
            ImeDiagnostics.logFocuslessComposition(
                    registeredFieldCount,
                    session.backend().isComposing(),
                    session.backend().currentPreeditText());
            return;
        }

        String committed;
        while ((committed = session.backend().pollCommittedText()) != null) {
            ImeDiagnostics.logCommittedText(committed);
            appendCommittedText(focused, committed);
        }
    }

    private void appendCommittedText(final TextFieldAPI focused,
                                     final String committed) {
        if (committed.isEmpty()) {
            return;
        }
        for (int i = 0; i < committed.length(); i++) {
            final char ch = committed.charAt(i);
            if (Character.isISOControl(ch)) {
                continue;
            }
            if (!focused.appendCharIfPossible(ch)) {
                final String existing = focused.getText() != null ? focused.getText() : "";
                focused.setText(existing + ch);
            }
        }
    }
}
