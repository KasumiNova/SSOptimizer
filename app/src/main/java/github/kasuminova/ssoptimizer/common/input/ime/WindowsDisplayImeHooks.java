package github.kasuminova.ssoptimizer.common.input.ime;

import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Windows 平台 IME 句柄解析辅助。
 * <p>
 * 通过反射读取 LWJGL 2 的 {@code Display -> WindowsDisplay} 实现对象，
 * 尝试解析当前窗口的 {@code HWND}，并在文本框聚焦时为
 * {@link WindowsImmImeBackend} 触发 attach。
 */
public final class WindowsDisplayImeHooks {
    private static final Logger LOGGER = Logger.getLogger(WindowsDisplayImeHooks.class);

    private static final Method DISPLAY_IS_CREATED = findMethod("org.lwjgl.opengl.Display", "isCreated");
    private static final Method DISPLAY_GET_IMPLEMENTATION = findMethod("org.lwjgl.opengl.Display", "getImplementation");
    private static final Method WINDOWS_DISPLAY_GET_HWND = findMethod("org.lwjgl.opengl.WindowsDisplay", "getHwnd");
    private static final Field WINDOWS_DISPLAY_HWND = findField("org.lwjgl.opengl.WindowsDisplay", "hwnd");

    private WindowsDisplayImeHooks() {
    }

    public static void afterCreateKeyboard(final Object windowsDisplay) {
        final ImeService service = ImeService.getInstance();
        final ImeBackend backend = service.backend();
        if (!(backend instanceof WindowsImmImeBackend windowsBackend)) {
            return;
        }

        try {
            final long hwnd = resolveWindowHandle(windowsDisplay);
            if (hwnd == 0L) {
                return;
            }
            windowsBackend.attach(0L, hwnd);
            final ImeCaretRect rect = service.computeCurrentCaretRect();
            if (rect != null) {
                windowsBackend.updateSpot(rect);
            }
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to attach Windows IME after keyboard creation: " + t.getMessage());
        }
    }

    public static void beforeDestroyKeyboard(final Object windowsDisplay) {
        final ImeBackend backend = ImeService.getInstance().backend();
        if (backend == null) {
            return;
        }

        try {
            backend.detach();
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to detach Windows IME before keyboard destroy: " + t.getMessage());
        }
    }

    /**
     * 每帧由 {@code WindowsDisplay.update()} 入口调用，
     * 轮询 IME native 层缓冲的提交文本并分发到当前聚焦的文本框。
     */
    public static void onUpdate() {
        try {
            final ImeService service = ImeService.getInstance();
            service.pollAndApplyCommittedText();
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to poll Windows IME committed text: " + t.getMessage());
        }
    }

    public static void onFocusChanged(final Object windowsDisplay,
                                      final boolean gotFocus) {
        try {
            final ImeService service = ImeService.getInstance();
            if (gotFocus) {
                ensureAttached(service.backend(), service);
            }
            service.onWindowFocusChanged(gotFocus);
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to update Windows IME focus state: " + t.getMessage());
        }
    }

    public static void ensureAttached(final ImeBackend backend,
                                      final ImeService service) {
        if (!(backend instanceof WindowsImmImeBackend windowsBackend) || !windowsBackend.isAvailable()) {
            return;
        }

        if (windowsBackend.hasActiveContext()) {
            return;
        }

        try {
            if (!displayCreated()) {
                return;
            }

            final long hwnd = resolveWindowHandle();
            if (hwnd == 0L) {
                return;
            }

            windowsBackend.attach(0L, hwnd);
            final ImeCaretRect rect = service != null ? service.computeCurrentCaretRect() : null;
            if (rect != null) {
                windowsBackend.updateSpot(rect);
            }
        } catch (Throwable t) {
            LOGGER.debug("[SSOptimizer] Failed to resolve Windows IME window handle: " + t.getMessage());
        }
    }

    static long resolveWindowHandle() {
        final Object implementation = invokeStatic(DISPLAY_GET_IMPLEMENTATION);
        return resolveWindowHandle(implementation);
    }

    static long resolveWindowHandle(final Object implementation) {
        if (implementation == null) {
            return 0L;
        }

        final Object hwndFromMethod = invoke(implementation, WINDOWS_DISPLAY_GET_HWND);
        if (hwndFromMethod instanceof Number number) {
            return number.longValue();
        }

        if (WINDOWS_DISPLAY_HWND != null) {
            try {
                final Object hwnd = WINDOWS_DISPLAY_HWND.get(implementation);
                if (hwnd instanceof Number number) {
                    return number.longValue();
                }
            } catch (IllegalAccessException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static boolean displayCreated() {
        final Object result = invokeStatic(DISPLAY_IS_CREATED);
        return result instanceof Boolean value && value;
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

    private static Field findField(final String className,
                                   final String fieldName) {
        try {
            final Class<?> clazz = Class.forName(className);
            final Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeStatic(final Method method) {
        try {
            return method != null ? method.invoke(null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke(final Object target,
                                 final Method method) {
        try {
            return target != null && method != null ? method.invoke(target) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}