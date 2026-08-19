package github.kasuminova.ssoptimizer.common.render.warroom;

import com.fs.graphics.TextureObject;

import java.awt.Color;

/**
 * 指挥界面任务连线条带的帧内收集缓冲。
 * <p>
 * 动机：原版 {@code TaskIconManager.render} 中每个任务图标会发起 3（并行连线）
 * × 2（箭头段）× 3（左右各偏移 0.5 的阴影层）= 18 次 {@code renderTexturedStrip}，
 * 每次调用都伴随独立的纹理绑定、JNI 调用与 draw call；且已摧毁/消失舰船的任务图标
 * 会进入 {@code taskIconList} 永久残留，每帧仍以 0 透明度完整渲染其连线。
 * <p>
 * 本类在 {@code TaskIconManager.render} 边界内把一帧内所有条带的参数收集到复用数组，
 * 结束时通过 {@link StripBatchRenderer} 单次提交；完全不可见（整体透明度为 0）的条带
 * 在收集时直接剔除——两种混合模式下 0 透明度写入均无视觉效果，剔除视觉等价。
 * <p>
 * 收集期间若纹理或混合模式发生变化，先 flush 已有段再开启新段，保证提交顺序与调用顺序一致。
 * <p>
 * 该优化默认开启，可通过 JVM 参数 {@code -Dssoptimizer.render.warroomtasks.enable=false} 关闭；
 * 关闭后 {@link #beginCollect()} 不生效，条带渲染走原有逐条路径。
 * <p>
 * 非线程安全：仅在渲染线程的 begin/end 边界内使用。
 */
public final class WarroomTaskLineBatch {
    /** 优化开关 JVM 属性名。 */
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.warroomtasks.enable";

    /** 每条带占用的几何 float 数（布局见 {@link StripBatchRenderer} 类注释）。 */
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

    /**
     * 返回是否启用任务连线合批优化。
     *
     * @return 默认 true，仅当 JVM 参数 {@code -Dssoptimizer.render.warroomtasks.enable=false} 时返回 false
     */
    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    /**
     * 返回当前是否处于收集状态（供条带渲染入口判断是否应转入批量路径）。
     *
     * @return 收集状态
     */
    public static boolean isCollecting() {
        return collecting;
    }

    /**
     * 返回当前已收集的条带数量（测试与诊断用）。
     *
     * @return 已收集条带数
     */
    public static int getStripCount() {
        return stripCount;
    }

    /**
     * 开启一次帧内收集。重复调用会先丢弃上一次未正常结束的数据（异常保护），再重新开启。
     */
    public static void beginCollect() {
        if (!isEnabled()) {
            return;
        }
        stripCount = 0;
        texture = null;
        additive = false;
        collecting = true;
    }

    /**
     * 收集一条条带。完全不可见的条带（颜色 alpha 为 0，或三段透明度缩放均不大于 0）被直接剔除。
     *
     * @param stripTexture        条带纹理
     * @param startX              起始点 X
     * @param startY              起始点 Y
     * @param endX                结束点 X
     * @param endY                结束点 Y
     * @param startWidth          起始段宽度
     * @param endWidth            结束段宽度
     * @param color               条带颜色
     * @param startEdgeAlphaScale 起始边透明度缩放
     * @param centerAlphaScale    中心透明度缩放
     * @param endEdgeAlphaScale   结束边透明度缩放
     * @param stripAdditive       是否加法混合
     * @param renderer            纹理或混合模式切换时用于 flush 已有段的执行器
     */
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

    /**
     * 结束收集并 flush 剩余条带。
     *
     * @param renderer 批量渲染执行器
     */
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
