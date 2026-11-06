package github.kasuminova.ssoptimizer.common.input.ime;

import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import github.kasuminova.ssoptimizer.common.font.EffectiveScreenScale;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.Display;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

final class ImeCaretLocator {
    private static final Logger LOGGER = Logger.getLogger(ImeCaretLocator.class);

    private final DoubleSupplier windowScaleSupplier;
    private final IntSupplier    windowHeightSupplier;

    ImeCaretLocator(final DoubleSupplier windowScaleSupplier,
                    final IntSupplier windowHeightSupplier) {
        this.windowScaleSupplier = windowScaleSupplier != null ? windowScaleSupplier : EffectiveScreenScale::current;
        this.windowHeightSupplier = windowHeightSupplier != null ? windowHeightSupplier : () -> Display.getHeight();
    }

    ImeCaretRect locate(final TextFieldAPI focused) {
        if (focused == null) {
            return null;
        }

        final PositionAPI fieldPosition = focused.getPosition();
        if (fieldPosition == null) {
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

        final PositionAPI caretPosition = caretPosition(fieldPosition, label);
        final float caretHeight = caretHeight(fieldPosition, label, text);
        final float scale = currentWindowScale();
        final int windowHeight = currentWindowHeight();

        return new ImeCaretRect(
                Math.round((caretPosition.getX() + textWidth) * scale),
                windowHeight - Math.round((caretPosition.getY() + caretHeight) * scale),
                Math.round(caretHeight * scale));
    }

    private PositionAPI caretPosition(final PositionAPI fieldPosition,
                                      final LabelAPI label) {
        if (label == null) {
            return fieldPosition;
        }

        final PositionAPI labelPosition = label.getPosition();
        return labelPosition != null ? labelPosition : fieldPosition;
    }

    private float caretHeight(final PositionAPI fieldPosition,
                              final LabelAPI label,
                              final String text) {
        if (label != null) {
            final PositionAPI labelPosition = label.getPosition();
            if (labelPosition != null && labelPosition.getHeight() > 0f) {
                return labelPosition.getHeight();
            }

            final float textHeight = label.computeTextHeight(text);
            if (Float.isFinite(textHeight) && textHeight > 0f) {
                return textHeight;
            }
        }
        return fieldPosition.getHeight();
    }

    private float currentWindowScale() {
        try {
            final double scale = windowScaleSupplier.getAsDouble();
            if (!Double.isFinite(scale) || scale <= 0.0d) {
                return 1.0f;
            }
            return (float) scale;
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Failed to read window scale; using 1.0.", e);
            return 1.0f;
        }
    }

    private int currentWindowHeight() {
        try {
            return Math.max(windowHeightSupplier.getAsInt(), 0);
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Failed to read window height; using 0.", e);
            return 0;
        }
    }
}
