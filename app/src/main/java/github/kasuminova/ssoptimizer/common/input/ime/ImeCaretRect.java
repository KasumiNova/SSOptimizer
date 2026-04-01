package github.kasuminova.ssoptimizer.common.input.ime;

/**
 * 输入法光标位置信息。
 * <p>
 * 记录当前输入光标的物理像素坐标和行高，传递给输入法后端用于定位候选框。
 */
public record ImeCaretRect(int x, int y, int height) {
}