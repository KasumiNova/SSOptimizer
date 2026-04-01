package github.kasuminova.ssoptimizer.common.input.ime;

import org.apache.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Linux 平台 IME 钩子类（由 ASM 在运行时注入调用）。
 * <p>
 * 提供 {@code onXEvent} 和 {@code onRawXEvent} 两个静态钩子，分别处理键盘事件
 * 和 XIM 协议消息（ClientMessage）。负责通过反射读取 LWJGL 的 LinuxDisplay/LinuxEvent
 * 内部状态，并将事件转发到 {@link ImeService} 和 {@link ImeBackend}。
 */
public final class LinuxDisplayImeHooks {
    private static final Logger LOGGER = Logger.getLogger(LinuxDisplayImeHooks.class);

    private static final Method         LINUX_DISPLAY_GET_DISPLAY   = findMethod("org.lwjgl.opengl.LinuxDisplay", "getDisplay");
    private static final Method         LINUX_DISPLAY_GET_WINDOW    = findMethod("org.lwjgl.opengl.LinuxDisplay", "getWindow");
    private static final Method         LINUX_EVENT_GET_TYPE        = findMethod("org.lwjgl.opengl.LinuxEvent", "getType");
    private static final Method         LINUX_EVENT_GET_WINDOW      = findMethod("org.lwjgl.opengl.LinuxEvent", "getWindow");
    private static final Method         LINUX_EVENT_GET_KEY_ADDRESS = findMethod("org.lwjgl.opengl.LinuxEvent", "getKeyAddress");
    private static final Class<?>       LINUX_EVENT_CLASS           = findClass("org.lwjgl.opengl.LinuxEvent");
    private static final Method         LINUX_EVENT_COPY_FROM       = LINUX_EVENT_CLASS != null
            ? findMethod("org.lwjgl.opengl.LinuxEvent", "copyFrom", LINUX_EVENT_CLASS)
            : null;
    private static final Constructor<?> LINUX_EVENT_CONSTRUCTOR     = findConstructor("org.lwjgl.opengl.LinuxEvent");
    private static final int            KEY_PRESS                   = findStaticInt("org.lwjgl.opengl.LinuxEvent", "KeyPress", 2);
    private static final int            KEY_RELEASE                 = findStaticInt("org.lwjgl.opengl.LinuxEvent", "KeyRelease", 3);

    private static volatile Object blankLinuxEvent;

    private LinuxDisplayImeHooks() {
    }

    public static void afterCreateKeyboard(final Object linuxDisplay) {
        final ImeService service = ImeService.getInstance();
        final ImeBackend backend = service.backend();
        if (backend == null) {
            return;
        }

        try {
            final long display = invokeStaticLong(LINUX_DISPLAY_GET_DISPLAY);
            final long window = invokeStaticLong(LINUX_DISPLAY_GET_WINDOW);
            backend.attach(display, window);
            final ImeCaretRect rect = service.computeCurrentCaretRect();
            if (rect != null) {
                backend.updateSpot(rect);
            }
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to attach IME after keyboard creation: " + t.getMessage());
        }
    }

    public static void beforeDestroyKeyboard(final Object linuxDisplay) {
        final ImeBackend backend = ImeService.getInstance().backend();
        if (backend == null) {
            return;
        }

        try {
            backend.detach();
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to detach IME before keyboard destroy: " + t.getMessage());
        }
    }

    public static void onFocusChanged(final Object linuxDisplay,
                                      final boolean gotFocus) {
        final ImeBackend backend = ImeService.getInstance().backend();
        if (backend == null) {
            return;
        }

        try {
            if (gotFocus) {
                backend.focusIn();
            } else {
                backend.focusOut();
            }
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to update IME focus state: " + t.getMessage());
        }
    }

    public static void onXEvent(final Object linuxDisplay,
                                final Object linuxEvent,
                                final boolean filteredByXim) {
        if (linuxEvent == null) {
            return;
        }

        final ImeService service = ImeService.getInstance();
        final ImeBackend backend = service.backend();
        if (backend == null) {
            return;
        }

        try {
            final int eventType = invokeInt(linuxEvent, LINUX_EVENT_GET_TYPE);

            // Forward ALL events to the native backend for XFilterEvent processing.
            // The XIM protocol requires every X event to pass through XFilterEvent,
            // not just key events — the IM uses FocusIn/Out, ClientMessage, etc.
            // to manage its internal state and commit text.
            final long keyEventAddress = invokeLong(linuxEvent, LINUX_EVENT_GET_KEY_ADDRESS);
            boolean consumed = backend.onX11KeyEvent(keyEventAddress, eventType);

            if (eventType == KEY_PRESS || eventType == KEY_RELEASE) {
                final long eventWindow = invokeLong(linuxEvent, LINUX_EVENT_GET_WINDOW);
                final long displayWindow = invokeStaticLong(LINUX_DISPLAY_GET_WINDOW);
                ImeDiagnostics.logX11KeyEvent(eventType, displayWindow, eventWindow, false, consumed);
                final String keyEventSummary = backend.lastKeyEventSummary();
                if (keyEventSummary != null && !keyEventSummary.isBlank()) {
                    LOGGER.info("[SSOptimizer] IME key event summary: " + keyEventSummary);
                }
                ImeDiagnostics.logPreeditState(eventType, backend.isComposing(), backend.currentPreeditText());
            }

            final ImeCaretRect rect = service.computeCurrentCaretRect();
            if (rect != null) {
                backend.updateSpot(rect);
            }
            service.pollAndApplyCommittedText();
            // Only clear key events — other event types (FocusIn/Out, ClientMessage, etc.)
            // must reach LWJGL for normal window management even if XFilterEvent consumed them.
            if (consumed && (eventType == KEY_PRESS || eventType == KEY_RELEASE)) {
                clearEvent(linuxEvent);
            }
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to process X11 IME event: " + t.getMessage());
        }
    }

    /**
     * Called immediately after {@code LinuxEvent.nextEvent()}, before LWJGL's
     * window-id check.  This ensures XIM protocol events (especially
     * {@code ClientMessage} type=33) that have a different window id are
     * still forwarded through {@code XFilterEvent} so that the input method
     * state machine can commit text.
     *
     * <p>Key events are intentionally skipped here — they are handled later
     * in {@link #onXEvent} after the window check and filterEvent call.
     */
    public static void onRawXEvent(final Object linuxEvent) {
        if (linuxEvent == null) {
            return;
        }
        final ImeBackend backend = ImeService.getInstance().backend();
        if (backend == null) {
            return;
        }
        try {
            final int eventType = invokeInt(linuxEvent, LINUX_EVENT_GET_TYPE);
            // Key events are processed by onXEvent after LWJGL's normal path.
            if (eventType == KEY_PRESS || eventType == KEY_RELEASE) {
                return;
            }
            final long addr = invokeLong(linuxEvent, LINUX_EVENT_GET_KEY_ADDRESS);
            final boolean consumed = backend.filterXimProtocolEvent(addr);
            if (consumed) {
                // Blank the event so LWJGL ignores it even if it passes
                // the window check.
                clearEvent(linuxEvent);
            }
        } catch (Throwable t) {
            // Silently ignore — this fires for every non-key event.
        }
    }

    private static Method findMethod(final String className,
                                     final String methodName,
                                     final Class<?>... parameterTypes) {
        try {
            final Class<?> clazz = Class.forName(className);
            final Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> findClass(final String className) {
        try {
            return Class.forName(className);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> findConstructor(final String className,
                                                  final Class<?>... parameterTypes) {
        try {
            final Class<?> clazz = Class.forName(className);
            final Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int findStaticInt(final String className,
                                     final String fieldName,
                                     final int defaultValue) {
        try {
            final Class<?> clazz = Class.forName(className);
            final Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private static long invokeStaticLong(final Method method) throws ReflectiveOperationException {
        if (method == null) {
            return 0L;
        }
        return ((Number) method.invoke(null)).longValue();
    }

    private static int invokeInt(final Object target,
                                 final Method method) throws ReflectiveOperationException {
        if (method == null) {
            return -1;
        }
        return ((Number) method.invoke(target)).intValue();
    }

    private static long invokeLong(final Object target,
                                   final Method method) throws ReflectiveOperationException {
        if (method == null) {
            return 0L;
        }
        return ((Number) method.invoke(target)).longValue();
    }

    private static void clearEvent(final Object linuxEvent) throws ReflectiveOperationException {
        if (linuxEvent == null || LINUX_EVENT_COPY_FROM == null) {
            return;
        }
        final Object blankEvent = blankLinuxEvent();
        if (blankEvent == null) {
            return;
        }
        LINUX_EVENT_COPY_FROM.invoke(linuxEvent, blankEvent);
    }

    private static Object blankLinuxEvent() throws ReflectiveOperationException {
        Object current = blankLinuxEvent;
        if (current != null) {
            return current;
        }
        if (LINUX_EVENT_CONSTRUCTOR == null) {
            return null;
        }
        synchronized (LinuxDisplayImeHooks.class) {
            current = blankLinuxEvent;
            if (current == null) {
                current = LINUX_EVENT_CONSTRUCTOR.newInstance();
                blankLinuxEvent = current;
            }
            return current;
        }
    }
}
