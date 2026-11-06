package github.kasuminova.ssoptimizer.common.input.ime;

import org.apache.log4j.Logger;

public final class LinuxDisplayImeHooks {
    private static final Logger LOGGER = Logger.getLogger(LinuxDisplayImeHooks.class);

    // X11 协议事件类型，见 X.h。
    private static final int KEY_PRESS   = 2;
    private static final int KEY_RELEASE = 3;

    private LinuxDisplayImeHooks() {
    }

    public static void afterCreateKeyboard(final long display,
                                           final long window) {
        final ImeCoordinator coordinator = ImeCoordinator.getInstance();
        final ImeBackend backend = coordinator.backend();

        try {
            backend.attach(display, window);
            final ImeCaretRect rect = coordinator.computeCurrentCaretRect();
            if (rect != null) {
                backend.updateSpot(rect);
            }
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to attach IME after keyboard creation: " + t.getMessage());
        }
    }

    public static void beforeDestroyKeyboard(final Object linuxDisplay) {
        final ImeBackend backend = ImeCoordinator.getInstance().backend();

        try {
            backend.detach();
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to detach IME before keyboard destroy: " + t.getMessage());
        }
    }

    public static void onFocusChanged(final Object linuxDisplay,
                                      final boolean gotFocus) {
        try {
            ImeCoordinator.getInstance().onWindowFocusChanged(gotFocus);
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to update IME focus state: " + t.getMessage());
        }
    }

    /**
     * 处理 LWJGL 窗口 ID 校验通过后的 X 事件。返回 {@code true} 表示事件已被 XIM 消费，调用方必须清空事件缓冲。
     */
    public static boolean onXEvent(final int eventType,
                                   final long keyEventAddress,
                                   final long eventWindow,
                                   final long displayWindow) {
        final ImeCoordinator coordinator = ImeCoordinator.getInstance();
        if (!coordinator.shouldCaptureImeInput()) {
            return false;
        }
        final ImeBackend backend = coordinator.backend();

        try {
            // XIM 要求所有 X 事件经过 XFilterEvent，以处理焦点、协议消息和文本提交。
            final boolean consumed = backend.onX11KeyEvent(keyEventAddress, eventType);
            final boolean keyEvent = eventType == KEY_PRESS || eventType == KEY_RELEASE;

            if (keyEvent) {
                ImeDiagnostics.logX11KeyEvent(eventType, displayWindow, eventWindow, false, consumed);
                final String keyEventSummary = backend.lastKeyEventSummary();
                if (ImeProperties.diagnosticsEnabled() && keyEventSummary != null && !keyEventSummary.isBlank()) {
                    LOGGER.info("[SSOptimizer] IME key event summary: " + keyEventSummary);
                }
                ImeDiagnostics.logPreeditState(eventType, backend.isComposing(), backend.currentPreeditText());
            }

            final ImeCaretRect rect = coordinator.computeCurrentCaretRect();
            if (rect != null) {
                backend.updateSpot(rect);
            }
            coordinator.pollAndApplyCommittedText();
            // 非键盘事件即使被 XIM 消费也必须交给 LWJGL 维护窗口状态。
            return consumed && keyEvent;
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to process X11 IME event: " + t.getMessage());
            return false;
        }
    }

    /**
     * 在 LWJGL 窗口 ID 校验前转发 XIM 协议事件，避免不同窗口 ID 的 ClientMessage 被丢弃。
     * 返回 {@code true} 表示事件已被 XIM 消费，调用方必须清空事件缓冲。
     */
    public static boolean onRawXEvent(final int eventType,
                                      final long eventAddress) {
        final ImeCoordinator coordinator = ImeCoordinator.getInstance();
        if (!coordinator.shouldCaptureImeInput()) {
            return false;
        }
        // 键盘事件由 onXEvent 处理，避免重复进入 XFilterEvent。
        if (eventType == KEY_PRESS || eventType == KEY_RELEASE) {
            return false;
        }
        try {
            return coordinator.backend().filterXimProtocolEvent(eventAddress);
        } catch (Exception t) {
            LOGGER.debug("[SSOptimizer] Failed to process raw X11 IME event.", t);
            return false;
        }
    }
}
