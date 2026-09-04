package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.TextureObject;
import com.fs.graphics.util.RenderStateUtils;
import com.fs.starfarer.prototype.Utils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/**
 * {@code TexturedStripRenderer} 圆弧渲染的顶点方向缓存重写
 * （A3：campaign indicator 渲染几何缓存）。
 * <p>
 * 注入动机：named 源码实证 {@code BaseLocation.renderIndicators} 的每帧热点并非
 * {@code UIIndicator}（其几何已被原版 {@code GLListManager} display list 缓存），
 * 而是 {@code EntityIndicator.renderRing}（height ≤ 3 的常规路径）逐帧调用的
 * {@code TexturedStripRenderer.renderArc}/{@code renderLineArc}——两者对圆弧上
 * 每个顶点即时计算一次 {@code Math.cos}/{@code Math.sin}
 * （named 源码 {@code TexturedStripRenderer.java:65-105/:168-220}），
 * 视口内每个实体每帧两次（主弧 + secondary fader 弧），超空间大图场景下构成
 * Profiler 中 {@code TexturedStripRenderer} 16.4% 的热点来源。
 * <p>
 * 方案选择（对比 display list）：弧顶点的方向向量完全由 {@code (step, count)}
 * 确定性派生（{@code step = span / count}，顶点 i 的角度为 {@code step * i}），
 * 因此按该键预计算 {@code (cos, sin)} 方向表进 float[] 缓存，渲染时查表后只做
 * 与原版逐次相同的半径乘法。相比 {@code GLListManager} display list 模式：
 * 不引入 display list 编译窗口（RT 桥接下编译窗口会强制窗口内全部命令退回
 * 逐指令 immediate 回放，且 {@code glGenLists} 走阻塞通道），与 bridge 的普通
 * 顶点流路径完全兼容。
 * <p>
 * 位级等价性论证：缓存条目本身就是用原版同一公式（同一 float 运算顺序、同一
 * {@code (float) Math.cos/sin} 收窄）一次性预计算的结果；逐帧渲染时读取缓存值后
 * 执行的乘法表达式与原版逐顶点表达式逐项一致（循环不变量 {@code radius - width}
 * 等的外提不改变 float 运算结果——每次迭代计算的都是同一表达式）。顶点位置之外的
 * 动态量（颜色/alpha、位移矩阵、线宽、混合模式）仍逐帧按原版逻辑计算。
 * <p>
 * 缓存上界：键为 {@code (step 位模式, count)}。稳定调用方（EntityIndicator 全圆弧、
 * 星座图标等）的键集有界且复用；跨帧连续变化 span 的调用方（如战斗电弧）会
 * 持续 miss——条目数达到 {@link #MAX_ENTRIES} 后新键不再入缓存（仍按同一公式
 * 即时计算，退化为原版成本），防止缓存无界增长。
 * <p>
 * 线程模型：与原版一致，仅渲染线程（RT 分离下为录制线程）触达，静态缓存无并发访问。
 */
public final class ArcStripRenderHelper {
    /** 方向表缓存条目数上限；满后新键退化为即时计算（语义与原版一致）。 */
    static final int MAX_ENTRIES = 1024;

    private static final float[] EMPTY = new float[0];
    private static final Long2ObjectOpenHashMap<float[]> DIRECTION_CACHE = new Long2ObjectOpenHashMap<>();

    private ArcStripRenderHelper() {
    }

    /**
     * {@code TexturedStripRenderer.renderArc} 12 参数重载的等价重写
     * （named 源码 :17-111），顶点方向向量改由 {@link #directions} 缓存供给。
     */
    public static void renderArc(
            TextureObject texture,
            float x, float y,
            float startAngle, float endAngle,
            float segmentLength,
            float radius, float width,
            Color color,
            int alphaStart, int alphaEnd,
            float alphaMult,
            boolean additive) {
        float startRad = (float) Math.toRadians(startAngle);
        float endRad = (float) Math.toRadians(endAngle);
        float span = Utils.normalizeAngle(endRad - startRad);
        float count = arcSegmentCount(span, radius, segmentLength);
        float step = span / count;

        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        GL11.glRotatef(startAngle, 0.0F, 0.0F, 1.0F);
        if (texture != null) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            texture.bind();
        } else {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
            GL11.glLineWidth(width);
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);

        float[] directions = directions(step, count);
        if (texture != null) {
            float innerRadius = radius - width;
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int i = 0; i < (int) count + 1; i++) {
                setArcColor(color, alphaStart, alphaEnd, alphaMult, i, count);
                float cos = directions[i * 2];
                float sin = directions[i * 2 + 1];
                GL11.glTexCoord2f(0.0F, 0.0F);
                GL11.glVertex2f(cos * radius, sin * radius);
                GL11.glTexCoord2f(0.0F, 1.0F);
                GL11.glVertex2f(cos * innerRadius, sin * innerRadius);
            }
            GL11.glEnd();
        } else {
            float midRadius = radius - width / 2.0F;
            GL11.glBegin(Math.abs(startAngle - endAngle) >= 360.0F ? GL11.GL_LINE_LOOP : GL11.GL_LINE_STRIP);
            for (int i = 0; i < (int) count + 1; i++) {
                setArcColor(color, alphaStart, alphaEnd, alphaMult, i, count);
                float cos = directions[i * 2];
                float sin = directions[i * 2 + 1];
                GL11.glVertex2f(cos * midRadius, sin * midRadius);
            }
            GL11.glEnd();
        }

        GL11.glPopMatrix();
        if (texture == null) {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
        }
    }

    /**
     * {@code TexturedStripRenderer.renderLineArc} 的等价重写
     * （named 源码 :113-225），顶点方向向量改由 {@link #directions} 缓存供给。
     */
    public static void renderLineArc(
            float x, float y,
            float startAngle, float endAngle,
            float segmentLength,
            float radius, float lineWidth,
            Color color, Color dashColor,
            int dashSegments,
            int alphaStart, int alphaEnd,
            float alphaMult,
            boolean additive) {
        float startRad = (float) Math.toRadians(startAngle);
        float endRad = (float) Math.toRadians(endAngle);
        float span = Utils.normalizeAngle(endRad - startRad);
        dashSegments = normalizeDashSegments(dashSegments);
        float count = lineArcSegmentCount(span, radius, segmentLength, dashSegments, dashColor != null);
        float step = span / count;

        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        GL11.glRotatef(startAngle, 0.0F, 0.0F, 1.0F);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(lineWidth);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);

        float[] directions = directions(step, count);
        float midRadius = radius - lineWidth / 2.0F;
        if (dashColor == null) {
            GL11.glBegin(Math.abs(startAngle - endAngle) >= 360.0F ? GL11.GL_LINE_LOOP : GL11.GL_LINE_STRIP);
            for (int i = 0; i < (int) count + 1; i++) {
                setArcColor(color, alphaStart, alphaEnd, alphaMult, i, count);
                GL11.glVertex2f(directions[i * 2] * midRadius, directions[i * 2 + 1] * midRadius);
            }
            GL11.glEnd();
        } else {
            GL11.glBegin(GL11.GL_LINES);
            Color current = dashColor;
            if (dashSegments > count) {
                dashSegments = (int) count;
            }
            // 原版为浮点取余（var30 % (int)(var18/var9)）：除数为 0 时得 NaN
            // （不等于 0.0F，不发生换色）而非抛异常，此处沿用浮点取余保持语义一致
            float dashLength = (int) (count / dashSegments);
            for (int i = 0; i < (int) count + 1; i++) {
                float alphaPos = 1.0F - 2.0F * Math.min(i / count, (count - i) / count);
                int alpha = (int) ((alphaEnd - alphaStart) * alphaPos) + alphaStart;
                alpha = (int) (alpha * alphaMult);
                float vx = directions[i * 2] * midRadius;
                float vy = directions[i * 2 + 1] * midRadius;
                if (i == 0) {
                    RenderStateUtils.setGlColor(current, alpha / 255.0F);
                    GL11.glVertex2f(vx, vy);
                }
                RenderStateUtils.setGlColor(current, alpha / 255.0F);
                GL11.glVertex2f(vx, vy);
                if (i % dashLength == 0.0F) {
                    if (current == dashColor) {
                        current = color;
                    } else if (current == color) {
                        current = dashColor;
                    }
                }
                if (i != (int) count) {
                    RenderStateUtils.setGlColor(current, alpha / 255.0F);
                    GL11.glVertex2f(vx, vy);
                }
            }
            GL11.glEnd();
        }

        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    /**
     * 原版 renderArc 的段数推导（named 源码 :35-39）：
     * {@code ceil(span * radius / segmentLength)} 后向上取整到 4 的倍数。
     */
    static float arcSegmentCount(float span, float radius, float segmentLength) {
        float count = (float) Math.ceil(span * radius / segmentLength);
        if ((int) count % 4 != 0) {
            count = (int) count / 4 * 4 + 4;
        }
        return count;
    }

    /**
     * 原版 renderLineArc 的段数推导（named 源码 :132-144）：
     * 虚线模式（dashColor != null）向上取整到 dashSegments 的倍数，否则到 4 的倍数。
     *
     * @param dashSegments 必须已经过 {@link #normalizeDashSegments} 归一化
     */
    static float lineArcSegmentCount(
            float span, float radius, float segmentLength, int dashSegments, boolean dashed) {
        float count = (float) Math.ceil(span * radius / segmentLength);
        if (dashed) {
            if ((int) count % dashSegments != 0) {
                count = (int) count / dashSegments * dashSegments + dashSegments;
            }
        } else if ((int) count % 4 != 0) {
            count = (int) count / 4 * 4 + 4;
        }
        return count;
    }

    /** 原版 renderLineArc 的 dashSegments 归一化（named 源码 :134-136）：非正数钳到 1。 */
    static int normalizeDashSegments(int dashSegments) {
        return dashSegments <= 0 ? 1 : dashSegments;
    }

    /**
     * 取 {@code (step, count)} 对应的圆弧方向表：布局为
     * {@code [cos0, sin0, cos1, sin1, ...]}，共 {@code count + 1} 个顶点。
     * 顶点 i 的角度为 {@code step * i}（与原版 float 循环计数器 {@code var19}
     * 逐值相等——int i 在可及范围内转 float 无精度损失），
     * {@code (float) Math.cos/sin} 与原版收窄方式一致，因此表内容与原版逐顶点
     * 即时计算结果位级一致。
     * <p>
     * 键为 {@code (step 原始位模式, (int) count)}：span/count 相同则整个顶点
     * 序列相同。缓存满（{@link #MAX_ENTRIES}）后新键不再入缓存，直接返回
     * 即时计算结果（与原版成本相当，不产生错误值）。
     *
     * @param count 段数；负值（radius 为负的退化输入）按原版语义产出零顶点
     */
    static float[] directions(float step, float count) {
        int n = (int) count;
        if (n < 0) {
            return EMPTY;
        }
        long key = (long) Float.floatToRawIntBits(step) << 32 | n & 0xFFFFFFFFL;
        float[] entry = DIRECTION_CACHE.get(key);
        if (entry != null) {
            return entry;
        }
        entry = new float[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            float angle = step * i;
            entry[i * 2] = (float) Math.cos(angle);
            entry[i * 2 + 1] = (float) Math.sin(angle);
        }
        if (DIRECTION_CACHE.size() < MAX_ENTRIES) {
            DIRECTION_CACHE.put(key, entry);
        }
        return entry;
    }

    /** 缓存条目数（测试观测点）。 */
    static int cacheSize() {
        return DIRECTION_CACHE.size();
    }

    /** 清空缓存（测试隔离入口；运行期无清空需求——缓存内容为纯函数结果，可永久存活）。 */
    static void clearCache() {
        DIRECTION_CACHE.clear();
    }

    /** 原版逐顶点 alpha 推导（renderArc :66-69 / renderLineArc :169-172），两方法共用同一公式。 */
    private static void setArcColor(
            Color color, int alphaStart, int alphaEnd, float alphaMult, int i, float count) {
        float alphaPos = 1.0F - 2.0F * Math.min(i / count, (count - i) / count);
        int alpha = (int) ((alphaEnd - alphaStart) * alphaPos) + alphaStart;
        alpha = (int) (alpha * alphaMult);
        RenderStateUtils.setGlColor(color, alpha);
    }
}
