package github.kasuminova.ssoptimizer.common.font.layout;

/**
 * {@link TextLayoutEngine} 的一次绘制输入快照：原版 BitmapFontRenderer 的全部渲染相关字段
 * 在 render() 入口的逐值拷贝。
 * <p>
 * 动机：布局引擎需要在不触碰游戏类的前提下复刻原版渲染语义；快照在 Mixin 内组装，
 * 引擎消费后不再回读 renderer，保证布局结果只由本快照决定。
 * alpha 族字段（textAlpha/borderAlpha/shadowAlpha/highlightAlpha/colorAlphas）直接取原版
 * setAlpha 预算好的结果，不在引擎内重算幂次分解。
 */
public record TextRenderState(
        String text,
        float drawX,
        float drawY,
        float fontSize,
        int textColorRgb,
        float textAlpha,
        int outlineColorRgb,
        float borderAlpha,
        float shadowAlpha,
        int highlightColorRgb,
        float highlightAlpha,
        int[] wordColorsRgb,
        float[] colorAlphas,
        int selectionStart,
        int selectionEnd,
        boolean[] charSelectionFlags,
        int[] charWordIndexes,
        boolean underlineEnabled,
        boolean shadowEnabled,
        boolean borderEnabled,
        float shadowOffsetX,
        float shadowOffsetY,
        int shadowCopies,
        float shadowScale,
        boolean compactFont,
        float shear,
        boolean visible) {

    public static Builder builder(String text) {
        return new Builder(text);
    }

    /** 字段较多，按项目规范走 Builder；默认值与原版字段初始值一致。 */
    public static final class Builder {
        private final String text;
        private float drawX;
        private float drawY;
        private float fontSize = 15f;
        private int textColorRgb = 0xFFFFFF;
        private float textAlpha = 1f;
        private int outlineColorRgb = 0x000000;
        private float borderAlpha = 1f;
        private float shadowAlpha = 1f;
        private int highlightColorRgb = 0xFFFFFF;
        private float highlightAlpha = 1f;
        private int[] wordColorsRgb;
        private float[] colorAlphas;
        private int selectionStart = -1;
        private int selectionEnd = -1;
        private boolean[] charSelectionFlags;
        private int[] charWordIndexes;
        private boolean underlineEnabled;
        private boolean shadowEnabled;
        private boolean borderEnabled;
        private float shadowOffsetX = 1f;
        private float shadowOffsetY = -1f;
        private int shadowCopies;
        private float shadowScale = 0.25f;
        private boolean compactFont;
        private float shear;
        private boolean visible;

        private Builder(String text) {
            this.text = text;
        }

        public Builder draw(float drawX, float drawY) {
            this.drawX = drawX;
            this.drawY = drawY;
            return this;
        }

        public Builder fontSize(float fontSize) {
            this.fontSize = fontSize;
            return this;
        }

        public Builder textColor(int rgb, float alpha) {
            this.textColorRgb = rgb;
            this.textAlpha = alpha;
            return this;
        }

        public Builder outlineColor(int rgb) {
            this.outlineColorRgb = rgb;
            return this;
        }

        public Builder borderAlpha(float borderAlpha) {
            this.borderAlpha = borderAlpha;
            return this;
        }

        public Builder shadowAlpha(float shadowAlpha) {
            this.shadowAlpha = shadowAlpha;
            return this;
        }

        public Builder highlightColor(int rgb, float alpha) {
            this.highlightColorRgb = rgb;
            this.highlightAlpha = alpha;
            return this;
        }

        public Builder wordColors(int[] rgb, float[] alphas) {
            this.wordColorsRgb = rgb;
            this.colorAlphas = alphas;
            return this;
        }

        public Builder selection(int start, int end) {
            this.selectionStart = start;
            this.selectionEnd = end;
            return this;
        }

        public Builder charSelection(boolean[] flags, int[] wordIndexes) {
            this.charSelectionFlags = flags;
            this.charWordIndexes = wordIndexes;
            return this;
        }

        public Builder underlineEnabled(boolean underlineEnabled) {
            this.underlineEnabled = underlineEnabled;
            return this;
        }

        public Builder shadowEnabled(boolean shadowEnabled) {
            this.shadowEnabled = shadowEnabled;
            return this;
        }

        public Builder borderEnabled(boolean borderEnabled) {
            this.borderEnabled = borderEnabled;
            return this;
        }

        public Builder shadowOffset(float x, float y) {
            this.shadowOffsetX = x;
            this.shadowOffsetY = y;
            return this;
        }

        public Builder outline(int copies, float scale) {
            this.shadowCopies = copies;
            this.shadowScale = scale;
            return this;
        }

        public Builder compactFont(boolean compactFont) {
            this.compactFont = compactFont;
            return this;
        }

        public Builder shear(float shear) {
            this.shear = shear;
            return this;
        }

        public Builder visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        public TextRenderState build() {
            return new TextRenderState(text, drawX, drawY, fontSize,
                    textColorRgb, textAlpha, outlineColorRgb, borderAlpha, shadowAlpha,
                    highlightColorRgb, highlightAlpha, wordColorsRgb, colorAlphas,
                    selectionStart, selectionEnd, charSelectionFlags, charWordIndexes,
                    underlineEnabled, shadowEnabled, borderEnabled,
                    shadowOffsetX, shadowOffsetY, shadowCopies, shadowScale,
                    compactFont, shear, visible);
        }
    }
}
