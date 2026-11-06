package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.TextureObject;
import com.fs.graphics.util.RenderStateUtils;
import com.fs.starfarer.prototype.Utils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/** 按 step 位模式与段数缓存圆弧方向；缓存满后即时计算，仅录制线程访问。 */
public final class ArcStripRenderHelper {
    static final int MAX_ENTRIES = 1024;

    private static final float[] EMPTY = new float[0];
    private static final Long2ObjectOpenHashMap<float[]> DIRECTION_CACHE = new Long2ObjectOpenHashMap<>();

    private ArcStripRenderHelper() {
    }

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
            // 浮点取余在除数为 0 时产生 NaN，不换色且不抛异常。
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

    static float arcSegmentCount(float span, float radius, float segmentLength) {
        float count = (float) Math.ceil(span * radius / segmentLength);
        if ((int) count % 4 != 0) {
            count = (int) count / 4 * 4 + 4;
        }
        return count;
    }

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

    static int normalizeDashSegments(int dashSegments) {
        return dashSegments <= 0 ? 1 : dashSegments;
    }

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

    static int cacheSize() {
        return DIRECTION_CACHE.size();
    }

    static void clearCache() {
        DIRECTION_CACHE.clear();
    }

    private static void setArcColor(
            Color color, int alphaStart, int alphaEnd, float alphaMult, int i, float count) {
        float alphaPos = 1.0F - 2.0F * Math.min(i / count, (count - i) / count);
        int alpha = (int) ((alphaEnd - alphaStart) * alphaPos) + alphaStart;
        alpha = (int) (alpha * alphaMult);
        RenderStateUtils.setGlColor(color, alpha);
    }
}
