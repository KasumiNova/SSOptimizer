package github.kasuminova.ssoptimizer.common.font;

/**
 * 字体渲染引擎版本开关。
 * <p>
 * 动机：P1-P3 期间新布局引擎（v2：{@code TextLayoutEngine} + {@code TextStreamEmitter}）
 * 与现行路径（legacy：drawGlyph @Overwrite + RuntimeScaledFontCache）并存，
 * 用 {@code -Dssoptimizer.font.engine=v2|legacy} 切换以便实机 A/B 与回滚；
 * P4 拆除 legacy 后本类随开关一并移除。
 */
public final class FontRenderEngine {

    private static final boolean V2 =
            "v2".equalsIgnoreCase(System.getProperty("ssoptimizer.font.engine", "legacy"));

    private FontRenderEngine() {
    }

    /** 是否启用 v2 布局引擎（默认 legacy，P2 翻转为 v2）。 */
    public static boolean isV2() {
        return V2;
    }
}
