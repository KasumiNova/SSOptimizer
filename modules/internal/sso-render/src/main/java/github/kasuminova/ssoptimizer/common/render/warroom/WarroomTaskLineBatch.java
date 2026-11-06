package github.kasuminova.ssoptimizer.common.render.warroom;

import com.fs.graphics.TextureObject;

import java.awt.Color;

/** 仅限渲染线程的收集边界内使用。 */
public final class WarroomTaskLineBatch {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.warroomtasks.enable";

    static final int FLOATS_PER_STRIP = 9;

    private static final int INITIAL_STRIP_CAPACITY = 256;

    private static float[] geometry = new float[INITIAL_STRIP_CAPACITY * FLOATS_PER_STRIP];
    private static int[] colors = new int[INITIAL_STRIP_CAPACITY];
    private static int stripCount;
    private static boolean collecting;
    private static TextureObject texture;
    private static boolean additive;

    private WarroomTaskLineBatch() {
    }

    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    public static boolean isCollecting() {
        return collecting;
    }

    public static int getStripCount() {
        return stripCount;
    }

    public static void beginCollect() {
        if (!isEnabled()) {
            return;
        }
        stripCount = 0;
        texture = null;
        additive = false;
        collecting = true;
    }

    public static void addStrip(TextureObject stripTexture,
                                float startX, float startY,
                                float endX, float endY,
                                float startWidth, float endWidth,
                                Color color,
                                float startEdgeAlphaScale,
                                float centerAlphaScale,
                                float endEdgeAlphaScale,
                                boolean stripAdditive,
                                StripBatchRenderer renderer) {
        if (!collecting) {
            return;
        }
        int alpha = color.getAlpha();
        if (alpha <= 0 || (startEdgeAlphaScale <= 0.0f && centerAlphaScale <= 0.0f && endEdgeAlphaScale <= 0.0f)) {
            return;
        }
        if (stripCount > 0 && (stripTexture != texture || stripAdditive != additive)) {
            flush(renderer);
        }
        texture = stripTexture;
        additive = stripAdditive;
        ensureCapacity(stripCount + 1);

        int base = stripCount * FLOATS_PER_STRIP;
        geometry[base] = startX;
        geometry[base + 1] = startY;
        geometry[base + 2] = endX;
        geometry[base + 3] = endY;
        geometry[base + 4] = startWidth;
        geometry[base + 5] = endWidth;
        geometry[base + 6] = startEdgeAlphaScale;
        geometry[base + 7] = centerAlphaScale;
        geometry[base + 8] = endEdgeAlphaScale;
        colors[stripCount] = (color.getRed() << 24) | (color.getGreen() << 16) | (color.getBlue() << 8) | alpha;
        stripCount++;
    }

    public static void endCollect(StripBatchRenderer renderer) {
        if (!collecting) {
            return;
        }
        collecting = false;
        flush(renderer);
    }

    private static void flush(StripBatchRenderer renderer) {
        if (stripCount == 0) {
            texture = null;
            return;
        }
        renderer.renderStripBatch(texture, additive, geometry, colors, stripCount);
        stripCount = 0;
        texture = null;
    }

    private static void ensureCapacity(int requiredStrips) {
        if (requiredStrips <= colors.length) {
            return;
        }
        int newCapacity = colors.length * 2;
        while (newCapacity < requiredStrips) {
            newCapacity *= 2;
        }
        float[] newGeometry = new float[newCapacity * FLOATS_PER_STRIP];
        System.arraycopy(geometry, 0, newGeometry, 0, stripCount * FLOATS_PER_STRIP);
        geometry = newGeometry;
        int[] newColors = new int[newCapacity];
        System.arraycopy(colors, 0, newColors, 0, stripCount);
        colors = newColors;
    }
}
