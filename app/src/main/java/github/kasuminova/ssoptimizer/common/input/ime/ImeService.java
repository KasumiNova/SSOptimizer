package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import github.kasuminova.ssoptimizer.common.font.EffectiveScreenScale;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.lwjgl.opengl.Display;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * 输入法服务中心。
 * <p>
 * 管理输入法后端的生命周期（attach/detach/focus），跟踪当前获得焦点的文本框，
 * 并将输入法提交的文本写入游戏 UI 控件。同时负责将 OpenGL 坐标转换为
 * X11/Win32 窗口坐标，并计算缩放后的光标位置传给后端。
 */
public final class ImeService {
    private static final ImeService INSTANCE                      = new ImeService(ImeBackends.createDefault(), EffectiveScreenScale::current, () -> Display.getHeight());
    private static final Method     GET_CURRENT_FOCUSED_COMPONENT = findMethod(
            GameClassNames.FOCUSED_COMPONENT_TRACKER.replace('/', '.'),
            GameMemberNames.FocusedComponentTracker.GET_CURRENT_FOCUSED_COMPONENT);

    private final    CopyOnWriteArrayList<WeakReference<TextFieldAPI>> registeredFields = new CopyOnWriteArrayList<>();
    private final    DoubleSupplier                                    windowScaleSupplier;
    private final    IntSupplier                                       windowHeightSupplier;
    private volatile ImeBackend                                        backend;
    private volatile WeakReference<TextFieldAPI>                       explicitlyFocusedField;
    private volatile boolean                                           windowFocused    = true;

    public ImeService(final ImeBackend backend) {
        this(backend, EffectiveScreenScale::current, () -> Display.getHeight());
    }

    ImeService(final ImeBackend backend,
               final DoubleSupplier windowScaleSupplier,
               final IntSupplier windowHeightSupplier) {
        this.backend = backend != null ? backend : new NoopImeBackend();
        this.windowScaleSupplier = windowScaleSupplier != null ? windowScaleSupplier : EffectiveScreenScale::current;
        this.windowHeightSupplier = windowHeightSupplier != null ? windowHeightSupplier : () -> Display.getHeight();
    }

    public static ImeService getInstance() {
        return INSTANCE;
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

    public ImeBackend backend() {
        return backend;
    }

    public void setBackend(final ImeBackend backend) {
        this.backend = backend != null ? backend : new NoopImeBackend();
    }

    public void register(final TextFieldAPI textField) {
        if (textField == null) {
            return;
        }
        ensureRegistered(textField);
        if (textField.hasFocus()) {
            syncImplicitFocusedField(textField);
        }
    }

    public void onFocusGained(final TextFieldAPI textField) {
        if (textField == null) {
            return;
        }
        ensureRegistered(textField);
        explicitlyFocusedField = new WeakReference<>(textField);
        if (windowFocused) {
            backend.focusIn();
        }
        final ImeCaretRect rect = computeCaretRect(textField);
        if (windowFocused && rect != null) {
            backend.updateSpot(rect);
        }
        ImeDiagnostics.logTextFieldFocus("focused", textField, registeredFieldCount());
    }

    public void onFocusLost(final TextFieldAPI textField) {
        final TextFieldAPI current = explicitlyFocusedField != null ? explicitlyFocusedField.get() : null;
        if (current == textField) {
            explicitlyFocusedField = null;
        }
        if (textField != null) {
            ImeDiagnostics.logTextFieldFocus("blurred", textField, registeredFieldCount());
        }

        final TextFieldAPI focused = currentFocusedField();
        if (focused == null || !windowFocused) {
            backend.focusOut();
            return;
        }

        final ImeCaretRect rect = computeCurrentCaretRect();
        if (rect != null) {
            backend.updateSpot(rect);
        }
    }

    void onWindowFocusChanged(final boolean focused) {
        windowFocused = focused;
        if (!focused) {
            backend.focusOut();
            return;
        }

        final TextFieldAPI currentFocusedField = currentFocusedField();
        if (currentFocusedField == null) {
            return;
        }

        backend.focusIn();
        final ImeCaretRect rect = computeCurrentCaretRect();
        if (rect != null) {
            backend.updateSpot(rect);
        }
    }

    boolean shouldCaptureImeInput() {
        return windowFocused && currentFocusedField() != null;
    }

    public ImeCaretRect computeCurrentCaretRect() {
        cleanupStaleReferences();
        return computeCaretRect(currentFocusedField());
    }

    public void pollAndApplyCommittedText() {
        cleanupStaleReferences();
        TextFieldAPI focused = currentFocusedField();
        if (focused == null) {
            ImeDiagnostics.logFocuslessComposition(registeredFieldCount(), backend.isComposing(), backend.currentPreeditText());
            return;
        }

        String committed;
        while ((committed = backend.pollCommittedText()) != null) {
            ImeDiagnostics.logCommittedText(committed);
            appendCommittedText(focused, committed);
        }
    }

    private void appendCommittedText(final TextFieldAPI focused, final String committed) {
        if (committed == null || committed.isEmpty()) {
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

    private TextFieldAPI currentFocusedField() {
        final TextFieldAPI explicit = explicitlyFocusedField != null ? explicitlyFocusedField.get() : null;
        if (explicit != null && explicit.hasFocus()) {
            return explicit;
        }
        explicitlyFocusedField = null;

        final TextFieldAPI globallyFocused = globalFocusedTextField();
        if (globallyFocused != null && globallyFocused.hasFocus()) {
            ensureRegistered(globallyFocused);
            syncImplicitFocusedField(globallyFocused);
            return globallyFocused;
        }

        for (WeakReference<TextFieldAPI> ref : registeredFields) {
            TextFieldAPI field = ref.get();
            if (field != null && field.hasFocus()) {
                syncImplicitFocusedField(field);
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
        if (windowFocused) {
            backend.focusIn();
            final ImeCaretRect rect = computeCaretRect(textField);
            if (rect != null) {
                backend.updateSpot(rect);
            }
        }
        ImeDiagnostics.logTextFieldFocus("focused-implicit", textField, registeredFieldCount());
    }

    private ImeCaretRect computeCaretRect(final TextFieldAPI focused) {
        if (focused == null) {
            return null;
        }

        final PositionAPI position = focused.getPosition();
        if (position == null) {
            return null;
        }

        String text = focused.getText();
        if (text == null) {
            text = "";
        }

        float textWidth = 0.0f;
        final LabelAPI label = focused.getTextLabelAPI();
        if (label != null) {
            textWidth = label.computeTextWidth(text);
        }

        final float scale = currentWindowScale();
        final int windowHeight = currentWindowHeight();
        final int scaledX = Math.round((position.getX() + textWidth) * scale);
        final int scaledY = windowHeight - Math.round((position.getY() + position.getHeight()) * scale);
        final int scaledHeight = Math.round(position.getHeight() * scale);

        return new ImeCaretRect(
                scaledX,
                scaledY,
                scaledHeight
        );
    }

    private TextFieldAPI globalFocusedTextField() {
        if (GET_CURRENT_FOCUSED_COMPONENT == null) {
            return null;
        }
        try {
            final Object focused = GET_CURRENT_FOCUSED_COMPONENT.invoke(null);
            if (focused instanceof TextFieldAPI textField) {
                return textField;
            }
        } catch (Throwable ignored) {
            // Best-effort reflection only.
        }
        return null;
    }

    private void cleanupStaleReferences() {
        for (Iterator<WeakReference<TextFieldAPI>> it = registeredFields.iterator(); it.hasNext(); ) {
            WeakReference<TextFieldAPI> ref = it.next();
            if (ref.get() == null) {
                registeredFields.remove(ref);
            }
        }
    }

    private float currentWindowScale() {
        try {
            final double scale = windowScaleSupplier.getAsDouble();
            if (!Double.isFinite(scale) || scale <= 0.0d) {
                return 1.0f;
            }
            return (float) scale;
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private int currentWindowHeight() {
        try {
            final int height = windowHeightSupplier.getAsInt();
            return Math.max(height, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int registeredFieldCount() {
        return registeredFields.size();
    }
}