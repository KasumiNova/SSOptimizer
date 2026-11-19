package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.render.EffectVertices;
import com.fs.starfarer.prototype.Utils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public final class ArcStripRenderHelper {
    static final int MAX_ENTRIES = 1024;

    private static final float[] EMPTY = new float[0];
    private static final ThreadLocal<Long2ObjectOpenHashMap<float[]>> DIRECTION_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);

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

        int mode = texture != null ? GL11.GL_TRIANGLE_STRIP : lineMode(startAngle, endAngle);
        new Arc(mode, count, step, radius, width, color, null, 1, alphaStart, alphaEnd, alphaMult).draw();

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

        int mode = dashColor == null ? lineMode(startAngle, endAngle) : GL11.GL_LINES;
        new Arc(mode, count, step, radius, lineWidth, color, dashColor, dashSegments,
                alphaStart, alphaEnd, alphaMult).draw();

        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private static int lineMode(float startAngle, float endAngle) {
        return Math.abs(startAngle - endAngle) >= 360.0F ? GL11.GL_LINE_LOOP : GL11.GL_LINE_STRIP;
    }

    record Arc(int mode, float count, float step, float radius, float width,
               Color color, Color dash, int dashSegments, int alphaStart, int alphaEnd,
               float alphaMult) implements EffectVertices.Mesh {
        void draw() {
            if (GlDispatch.canSubmitOwnedDraw() && color != null && color.getClass() == Color.class
                    && (dash == null || dash.getClass() == Color.class)) {
                EffectVertices.submit(this);
            } else {
                emit(EffectVertices.RECORDED);
            }
        }

        @Override
        public void emit(EffectVertices out) {
            float[] directions = directions(step, count);
            float mid = radius - width / 2.0F;
            float inner = radius - width;
            Color current = dash;
            float dashLength = dash == null ? 0.0F : (int) (count / Math.min(dashSegments, (int) count));
            out.begin(mode);
            try {
                for (int i = 0; i < (int) count + 1; i++) {
                    float pos = 1.0F - 2.0F * Math.min(i / count, (count - i) / count);
                    int alpha = (int) (((int) ((alphaEnd - alphaStart) * pos) + alphaStart) * alphaMult);
                    float c = directions[i * 2];
                    float s = directions[i * 2 + 1];
                    if (dash == null) {
                        setColor(out, color, alpha);
                        if (mode == GL11.GL_TRIANGLE_STRIP) {
                            out.uv(0.0F, 0.0F);
                            out.vertex(c * radius, s * radius);
                            out.uv(0.0F, 1.0F);
                            out.vertex(c * inner, s * inner);
                        } else {
                            out.vertex(c * mid, s * mid);
                        }
                    } else {
                        if (i == 0) dashVertex(out, current, alpha, c * mid, s * mid);
                        dashVertex(out, current, alpha, c * mid, s * mid);
                        if (i % dashLength == 0.0F) current = current == dash ? color : dash;
                        if (i != (int) count) dashVertex(out, current, alpha, c * mid, s * mid);
                    }
                }
            } finally {
                out.end();
            }
        }

        private static void dashVertex(EffectVertices out, Color color, int alpha, float x, float y) {
            byte r = (byte) color.getRed(), g = (byte) color.getGreen(), b = (byte) color.getBlue();
            out.color(r, g, b, (byte) (color.getAlpha() * (alpha / 255.0F)));
            out.vertex(x, y);
        }

        private static void setColor(EffectVertices out, Color color, int alpha) {
            out.color((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) alpha);
        }
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
        var cache = DIRECTION_CACHE.get();
        float[] entry = cache.get(key);
        if (entry != null) {
            return entry;
        }
        entry = new float[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            float angle = step * i;
            entry[i * 2] = (float) Math.cos(angle);
            entry[i * 2 + 1] = (float) Math.sin(angle);
        }
        if (cache.size() < MAX_ENTRIES) {
            cache.put(key, entry);
        }
        return entry;
    }

    static int cacheSize() {
        return DIRECTION_CACHE.get().size();
    }

    static void clearCache() {
        DIRECTION_CACHE.remove();
    }

}
