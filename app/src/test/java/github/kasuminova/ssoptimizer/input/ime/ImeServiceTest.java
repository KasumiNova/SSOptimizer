package github.kasuminova.ssoptimizer.input.ime;

import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ImeServiceTest {
    @Test
    void selectsFocusedTextFieldAndComputesSpotFromPublicApi() {
        FakeTextField unfocused = new FakeTextField(false, 100f, 200f, 160f, 24f, "abc", 24f);
        FakeTextField focused = new FakeTextField(true, 300f, 400f, 220f, 24f, "中文", 36f);

        ImeService service = new ImeService(new NoopImeBackend(), () -> 1.0d, () -> 824);
        service.register(unfocused);
        service.register(focused);

        ImeCaretRect rect = service.computeCurrentCaretRect();
        assertNotNull(rect);
        assertEquals(336, rect.x());
        assertEquals(400, rect.y());
        assertEquals(24, rect.height());
    }

    @Test
    void appliesCommittedTextIntoFocusedField() {
        FakeTextField focused = new FakeTextField(true, 10f, 20f, 200f, 24f, "", 0f);
        FakeImeBackend backend = new FakeImeBackend(List.of("中", "文"));

        ImeService service = new ImeService(backend, () -> 1.0d, () -> 824);
        service.register(focused);
        service.pollAndApplyCommittedText();

        assertEquals("中文", focused.getText());
    }

    @Test
    void ignoresControlCharactersInCommittedText() {
        FakeTextField focused = new FakeTextField(true, 10f, 20f, 200f, 24f, "abc", 0f);
        FakeImeBackend backend = new FakeImeBackend(List.of("\b", "中"));

        ImeService service = new ImeService(backend, () -> 1.0d, () -> 824);
        service.register(focused);
        service.pollAndApplyCommittedText();

        assertEquals("abc中", focused.getText());
    }

    @Test
    void textFieldFocusDrivesImeFocusAndSpotUpdate() {
        FakeTextField focused = new FakeTextField(true, 300f, 400f, 220f, 24f, "中文", 36f);
        FakeImeBackend backend = new FakeImeBackend(List.of());

        ImeService service = new ImeService(backend, () -> 1.0d, () -> 824);
        service.onFocusGained(focused);

        assertEquals(1, backend.focusInCount);
        assertNotNull(backend.lastSpot);
        assertEquals(336, backend.lastSpot.x());
        assertEquals(400, backend.lastSpot.y());
        assertEquals(24, backend.lastSpot.height());

        focused.setFocused(false);
        service.onFocusLost(focused);
        assertEquals(1, backend.focusOutCount);
    }

    @Test
    void scalesCaretSpotByWindowScale() {
        FakeTextField focused = new FakeTextField(true, 300f, 400f, 220f, 24f, "中文", 36f);
        ImeService service = new ImeService(new NoopImeBackend(), () -> 1.5d, () -> 824);
        service.register(focused);

        ImeCaretRect rect = service.computeCurrentCaretRect();

        assertNotNull(rect);
        assertEquals(Math.round((300f + 36f) * 1.5f), rect.x());
        assertEquals(824 - Math.round((400f + 24f) * 1.5f), rect.y());
        assertEquals(Math.round(24f * 1.5f), rect.height());
    }

    @Test
    void windowsBackendStubReportsUnavailable() {
        assertFalse(new WindowsImmImeBackend().isAvailable());
    }

    private static final class FakeImeBackend implements ImeBackend {
        private final java.util.ArrayDeque<String> commits = new java.util.ArrayDeque<>();
        private int focusInCount;
        private int focusOutCount;
        private ImeCaretRect lastSpot;

        private FakeImeBackend(List<String> commits) {
            this.commits.addAll(commits);
        }

        @Override public boolean isAvailable() { return true; }
        @Override public void attach(long display, long window) {}
        @Override public void detach() {}
        @Override public void focusIn() { focusInCount++; }
        @Override public void focusOut() { focusOutCount++; }
        @Override public void updateSpot(ImeCaretRect rect) { lastSpot = rect; }
        @Override public boolean onX11KeyEvent(long keyEventAddress, int eventType) { return false; }
        @Override public String lastKeyEventSummary() { return ""; }
        @Override public String pollCommittedText() { return commits.pollFirst(); }
        @Override public String currentPreeditText() { return ""; }
        @Override public boolean isComposing() { return false; }
        @Override public boolean filterXimProtocolEvent(long eventAddress) { return false; }
    }

    private static final class FakeTextField implements TextFieldAPI {
        private final FakePosition position;
        private final FakeLabel label;
        private boolean focused;
        private String text;
        private float opacity = 1f;

        private FakeTextField(boolean focused, float x, float y, float width, float height, String text, float textWidth) {
            this.focused = focused;
            this.position = new FakePosition(x, y, width, height);
            this.label = new FakeLabel(text, textWidth, x, y, width, height);
            this.text = text;
        }

        @Override public PositionAPI getPosition() { return position; }
        @Override public LabelAPI getTextLabelAPI() { return label; }
        @Override public String getText() { return text; }
        @Override public void setText(String var1) { this.text = var1; this.label.text = var1; }
        @Override public boolean hasFocus() { return focused; }
        @Override public boolean appendCharIfPossible(char var1) { this.text += var1; this.label.text += var1; return true; }

        private void setFocused(boolean focused) { this.focused = focused; }

        @Override public void setPad(float var1) {}
        @Override public void setMidAlignment() {}
        @Override public void setColor(Color var1) {}
        @Override public void setBgColor(Color var1) {}
        @Override public boolean isValidChar(char var1) { return true; }
        @Override public boolean isLimitByStringWidth() { return false; }
        @Override public void setLimitByStringWidth(boolean var1) {}
        @Override public boolean appendCharIfPossible(char var1, boolean var2) { return appendCharIfPossible(var1); }
        @Override public int getMaxChars() { return 0; }
        @Override public void setMaxChars(int var1) {}
        @Override public void deleteAll() {}
        @Override public void deleteAll(boolean var1) {}
        @Override public void deleteLastWord() {}
        @Override public void grabFocus() {}
        @Override public void grabFocus(boolean var1) {}
        @Override public boolean isUndoOnEscape() { return false; }
        @Override public void setUndoOnEscape(boolean var1) {}
        @Override public boolean isHandleCtrlV() { return false; }
        @Override public void setHandleCtrlV(boolean var1) {}
        @Override public Color getBorderColor() { return Color.WHITE; }
        @Override public void setBorderColor(Color var1) {}
        @Override public boolean isVerticalCursor() { return true; }
        @Override public void setVerticalCursor(boolean var1) {}
        @Override public void hideCursor() {}
        @Override public void showCursor() {}
        @Override public void render(float var1) {}
        @Override public void processInput(List<InputEventAPI> var1) {}
        @Override public void advance(float amount) {}
        @Override public void setOpacity(float opacity) { this.opacity = opacity; }
        @Override public float getOpacity() { return opacity; }
    }

    private static final class FakePosition implements PositionAPI {
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private boolean suspendRecompute;

        private FakePosition(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override public float getX() { return x; }
        @Override public float getY() { return y; }
        @Override public float getWidth() { return width; }
        @Override public float getHeight() { return height; }
        @Override public float getCenterX() { return x + width / 2f; }
        @Override public float getCenterY() { return y + height / 2f; }
        @Override public PositionAPI setLocation(float var1, float var2) { throw new UnsupportedOperationException(); }
        @Override public PositionAPI setSize(float var1, float var2) { throw new UnsupportedOperationException(); }
        @Override public boolean containsEvent(InputEventAPI var1) { return false; }
        @Override public PositionAPI setXAlignOffset(float var1) { return this; }
        @Override public PositionAPI setYAlignOffset(float var1) { return this; }
        @Override public PositionAPI inTL(float var1, float var2) { return this; }
        @Override public PositionAPI inTMid(float var1) { return this; }
        @Override public PositionAPI inTR(float var1, float var2) { return this; }
        @Override public PositionAPI inRMid(float var1) { return this; }
        @Override public PositionAPI inMid() { return this; }
        @Override public PositionAPI inBR(float var1, float var2) { return this; }
        @Override public PositionAPI inBMid(float var1) { return this; }
        @Override public PositionAPI inBL(float var1, float var2) { return this; }
        @Override public PositionAPI inLMid(float var1) { return this; }
        @Override public PositionAPI leftOfTop(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI leftOfMid(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI leftOfBottom(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI rightOfTop(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI rightOfMid(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI rightOfBottom(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI aboveLeft(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI aboveMid(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI aboveRight(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI belowLeft(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI belowMid(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public PositionAPI belowRight(com.fs.starfarer.api.ui.UIComponentAPI var1, float var2) { return this; }
        @Override public void setSuspendRecompute(boolean suspendRecompute) { this.suspendRecompute = suspendRecompute; }
        @Override public boolean isSuspendRecompute() { return suspendRecompute; }
    }

    private static final class FakeLabel implements LabelAPI {
        private String text;
        private final float width;
        private final FakePosition position;

        private FakeLabel(String text, float width, float x, float y, float fieldWidth, float fieldHeight) {
            this.text = text;
            this.width = width;
            this.position = new FakePosition(x, y, fieldWidth, fieldHeight);
        }

        @Override public String getText() { return text; }
        @Override public void setText(String var1) { this.text = var1; }
        @Override public PositionAPI getPosition() { return position; }
        @Override public float computeTextWidth(String var1) { return width; }
        @Override public float computeTextHeight(String var1) { return position.getHeight(); }

        @Override public void setHighlight(int var1, int var2) {}
        @Override public void highlightFirst(String var1) {}
        @Override public void highlightLast(String var1) {}
        @Override public void setHighlight(String... var1) {}
        @Override public void unhighlightIndex(int var1) {}
        @Override public void setHighlightColor(Color var1) {}
        @Override public void setHighlightColors(Color... var1) {}
        @Override public void setAlignment(Alignment var1) {}
        @Override public float getOpacity() { return 1f; }
        @Override public void setOpacity(float var1) {}
        @Override public void italicize() {}
        @Override public void italicize(float var1) {}
        @Override public void unitalicize() {}
        @Override public PositionAPI autoSizeToWidth(float var1) { return position; }
        @Override public void flash(float var1, float var2) {}
        @Override public void render(float var1) {}
        @Override public void advance(float var1) {}
        @Override public void setHighlightOnMouseover(boolean var1) {}
        @Override public Color getColor() { return Color.WHITE; }
        @Override public void setColor(Color var1) {}
    }
}
