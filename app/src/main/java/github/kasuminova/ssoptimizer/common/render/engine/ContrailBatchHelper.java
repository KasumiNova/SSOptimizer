package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;

/**
 * Batches original engine {@code ContrailEngine} strips per group and flushes
 * them with a single {@code glDrawArrays(GL_QUAD_STRIP)} call.
 * <p>
 * The original renderer already groups by texture/blend mode, so this helper
 * preserves those boundaries and only removes the per-segment immediate-mode
 * {@code glColor4ub/glTexCoord2f/glVertex2f} call storm.
 */
public final class ContrailBatchHelper {
    private static final int   MAX_VERTICES           = 262_144;
    private static final float V_MIN                  = 0.01f;
    private static final float V_MAX                  = 0.99f;
    private static final int   GL_SRC_ALPHA           = 770;
    private static final int   GL_ONE                 = 1;
    private static final int   GL_ONE_MINUS_SRC_ALPHA = 771;

    private static ByteBuffer  colorBuf;
    private static FloatBuffer vertexBuf;
    private static FloatBuffer texCoordBuf;
    private static int         numVertices;

    private ContrailBatchHelper() {
    }

    public static void renderContrails(Object groupsObject, float alphaScale) {
        if (!(groupsObject instanceof Map<?, ?> groups) || groups.isEmpty()) {
            return;
        }

        for (Object group : groups.values()) {
            renderGroup((ContrailGroupAccessor) group, alphaScale);
        }
    }

    private static void renderGroup(ContrailGroupAccessor groupFields, float alphaScale) {
        if (groupFields == null) {
            return;
        }

        try {
            List<Object> segments = groupFields.ssoptimizer$getSegments();
            if (segments == null || segments.size() <= 1) {
                return;
            }

            com.fs.graphics.Object texture = groupFields.ssoptimizer$getTexture();
            if (texture == null) {
                return;
            }

            applyBlendMode(groupFields.ssoptimizer$getBlendMode());
            texture.Ø00000();

            Color color = groupFields.ssoptimizer$getColor();
            if (color == null) {
                return;
            }

            Vector2f tailPoint = groupFields.ssoptimizer$getTail();
            int red = color.getRed();
            int green = color.getGreen();
            int blue = color.getBlue();
            int baseAlpha = color.getAlpha();

            beginStrip();

            ContrailSegmentAccessor lastSegment = null;
            for (Object segmentObject : segments) {
                ContrailSegmentAccessor segment = (ContrailSegmentAccessor) segmentObject;

                float maxAge = segment.ssoptimizer$getMaxAge();
                float fadeWindow = maxAge <= 0.0f ? 0.5f : 0.05f / maxAge;
                if (fadeWindow > 0.5f) {
                    fadeWindow = 0.5f;
                }

                float progress = segment.ssoptimizer$getProgress();
                float brightness;
                if (progress < fadeWindow) {
                    brightness = progress * 10.0f;
                } else {
                    brightness = (1.0f - progress) / (1.0f - fadeWindow);
                }
                brightness *= alphaScale;

                int alpha = clampColorComponent((int) (baseAlpha
                        * segment.ssoptimizer$getAlphaMult()
                        * brightness));

                Vector2f position = segment.ssoptimizer$getPosition();
                Vector2f normal = segment.ssoptimizer$getNormal();
                float width = segment.ssoptimizer$getWidth();
                float halfWidth = width * 0.5f;

                addPair(
                        red, green, blue, alpha,
                        segment.ssoptimizer$getU(),
                        position.x - normal.x * halfWidth,
                        position.y - normal.y * halfWidth,
                        position.x + normal.x * halfWidth,
                        position.y + normal.y * halfWidth
                );
                lastSegment = segment;
            }

            if (tailPoint != null && lastSegment != null) {
                Vector2f normal = lastSegment.ssoptimizer$getNormal();
                float width = lastSegment.ssoptimizer$getWidth() * 0.25f;
                float u = lastSegment.ssoptimizer$getU();

                addPair(
                        red, green, blue, 0,
                        u,
                        tailPoint.x - normal.x * width,
                        tailPoint.y - normal.y * width,
                        tailPoint.x + normal.x * width,
                        tailPoint.y + normal.y * width
                );
            }

            flushStrip();
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to render ContrailEngine group", e);
        }
    }

    private static void beginStrip() {
        ensureBuffers();
        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
    }

    private static void ensureBuffers() {
        if (colorBuf != null) {
            return;
        }

        colorBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 4)
                             .order(ByteOrder.nativeOrder());
        vertexBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 2 * 4)
                              .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 2 * 4)
                                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static void addPair(int r, int g, int b, int a,
                                float u,
                                float leftX, float leftY,
                                float rightX, float rightY) {
        if (numVertices + 2 > MAX_VERTICES) {
            flushStrip();
        }

        byte rb = (byte) r;
        byte gb = (byte) g;
        byte bb = (byte) b;
        byte ab = (byte) a;

        colorBuf.put(rb).put(gb).put(bb).put(ab);
        colorBuf.put(rb).put(gb).put(bb).put(ab);

        texCoordBuf.put(u).put(V_MIN);
        texCoordBuf.put(u).put(V_MAX);

        vertexBuf.put(leftX).put(leftY);
        vertexBuf.put(rightX).put(rightY);

        numVertices += 2;
    }

    private static void flushStrip() {
        if (numVertices == 0) {
            return;
        }

        colorBuf.flip();
        vertexBuf.flip();
        texCoordBuf.flip();

        int finalColorIndex = (numVertices - 1) * 4;
        byte finalRed = colorBuf.get(finalColorIndex);
        byte finalGreen = colorBuf.get(finalColorIndex + 1);
        byte finalBlue = colorBuf.get(finalColorIndex + 2);
        byte finalAlpha = colorBuf.get(finalColorIndex + 3);

        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            GL11.glColorPointer(4, true, 0, colorBuf);
            GL11.glVertexPointer(2, 0, vertexBuf);
            GL11.glTexCoordPointer(2, 0, texCoordBuf);

            GL11.glDrawArrays(GL11.GL_QUAD_STRIP, 0, numVertices);
        } finally {
            GL11.glPopClientAttrib();
        }

        GL11.glColor4ub(finalRed, finalGreen, finalBlue, finalAlpha);

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
    }

    private static void applyBlendMode(Object blendMode) {
        if (isGlowBlendMode(blendMode)) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    private static boolean isGlowBlendMode(Object blendMode) {
        return blendMode instanceof Enum<?> mode && "GLOW".equals(mode.name());
    }

    private static int clampColorComponent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }
}