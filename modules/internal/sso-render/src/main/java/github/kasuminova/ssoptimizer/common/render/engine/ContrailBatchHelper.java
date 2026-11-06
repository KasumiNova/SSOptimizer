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

    private static boolean hasFlushedStrip;
    private static byte finalR;
    private static byte finalG;
    private static byte finalB;
    private static byte finalA;
    private static float lastU;
    private static float lastX;
    private static float lastY;
    private static byte  lastR;
    private static byte  lastG;
    private static byte  lastB;
    private static byte  lastA;

    private ContrailBatchHelper() {
    }

    public static void renderContrails(Object groupsObject, float alphaScale) {
        if (!(groupsObject instanceof Map<?, ?> groups) || groups.isEmpty()) {
            return;
        }

        try {
            renderAllGroups(groups, alphaScale);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to render ContrailEngine group", e);
        }
    }

    private static void renderAllGroups(Map<?, ?> groups, float alphaScale) {
        beginStrip();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            Object activeBlend = new Object();
            Object boundTexture = null;
            for (Object groupObject : groups.values()) {
                ContrailGroupAccessor group = (ContrailGroupAccessor) groupObject;
                if (group == null) {
                    continue;
                }

                Object blend = group.ssoptimizer$getBlendMode();
                if (activeBlend != blend) {
                    flushStrip();
                    applyBlendMode(blend);
                    activeBlend = blend;
                }

                com.fs.graphics.TextureObject texture = group.ssoptimizer$getTexture();
                if (texture == null) {
                    continue;
                }
                List<Object> segments = group.ssoptimizer$getSegments();
                if (segments == null || segments.size() <= 1) {
                    continue;
                }
                if (boundTexture != texture) {
                    texture.bind();
                    boundTexture = texture;
                }

                if (!encodeGroup(group, alphaScale)) {
                    flushStrip();
                    restoreCurrentColor();
                    return;
                }
            }

            flushStrip();
            restoreCurrentColor();
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    static boolean encodeGroup(ContrailGroupAccessor group, float alphaScale) {
        Color color = group.ssoptimizer$getColor();
        if (color == null) {
            return true;
        }
        Vector2f tailPoint = group.ssoptimizer$getTail();
        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        int baseAlpha = color.getAlpha();

        emitJoinConnector();

        float segmentDuration = group.ssoptimizer$getSegmentDuration();
        float fadeWindow = segmentDuration <= 0.0f ? 0.5f : 0.05f / segmentDuration;
        if (fadeWindow > 0.5f) {
            fadeWindow = 0.5f;
        }

        List<Object> segments = group.ssoptimizer$getSegments();
        ContrailSegmentAccessor lastSegment = null;
        for (final Object segmentObj : segments) {
            ContrailSegmentAccessor segment = (ContrailSegmentAccessor) segmentObj;

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
            if (position == null || normal == null) {
                continue;
            }
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
            if (normal == null) {
                return false;
            }
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
        return true;
    }

    static void beginStrip() {
        ensureBuffers();
        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
        hasFlushedStrip = false;
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

    /** 重复末顶点形成零面积四边形，避免连接不同组时产生碎片。 */
    private static void emitJoinConnector() {
        if (numVertices == 0) {
            return;
        }
        if (numVertices + 2 > MAX_VERTICES) {
            flushStrip();
            return;
        }
        putVertex(lastU, lastX, lastY, lastR, lastG, lastB, lastA);
        putVertex(lastU, lastX, lastY, lastR, lastG, lastB, lastA);
    }

    /** 连接点不是新的段末顶点，不得更新 lastU、lastX 等跟踪字段。 */
    private static void putVertex(float u, float x, float y, byte r, byte g, byte b, byte a) {
        colorBuf.put(r).put(g).put(b).put(a);
        texCoordBuf.put(u).put(V_MAX);
        vertexBuf.put(x).put(y);
        numVertices++;
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

        lastU = u;
        lastX = rightX;
        lastY = rightY;
        lastR = rb;
        lastG = gb;
        lastB = bb;
        lastA = ab;
    }

    private static void flushStrip() {
        if (numVertices == 0) {
            return;
        }

        colorBuf.flip();
        vertexBuf.flip();
        texCoordBuf.flip();

        int finalColorIndex = (numVertices - 1) * 4;
        finalR = colorBuf.get(finalColorIndex);
        finalG = colorBuf.get(finalColorIndex + 1);
        finalB = colorBuf.get(finalColorIndex + 2);
        finalA = colorBuf.get(finalColorIndex + 3);
        hasFlushedStrip = true;

        GL11.glColorPointer(4, true, 0, colorBuf);
        GL11.glVertexPointer(2, 0, vertexBuf);
        GL11.glTexCoordPointer(2, 0, texCoordBuf);

        GL11.glDrawArrays(GL11.GL_QUAD_STRIP, 0, numVertices);

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
    }

    /** 仅在发生绘制后恢复最后末顶点颜色，与原版 current color 的可见结果一致。 */
    private static void restoreCurrentColor() {
        if (!hasFlushedStrip) {
            return;
        }
        GL11.glColor4ub(finalR, finalG, finalB, finalA);
    }

    private static void applyBlendMode(Object blendMode) {
        if (isGlowBlendMode(blendMode)) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    static boolean isGlowBlendMode(Object blendMode) {
        return blendMode instanceof Enum<?> mode && "GLOW".equals(mode.name());
    }

    static int clampColorComponent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    static int getNumVertices() {
        return numVertices;
    }

    static EncodedVertex vertexAt(int index) {
        if (index < 0 || index >= numVertices) {
            throw new IndexOutOfBoundsException(
                    "vertex index " + index + " out of [0, " + numVertices + ")");
        }
        int colorIndex = index * 4;
        int coordIndex = index * 2;
        return new EncodedVertex(
                vertexBuf.get(coordIndex),
                vertexBuf.get(coordIndex + 1),
                texCoordBuf.get(coordIndex),
                texCoordBuf.get(coordIndex + 1),
                colorBuf.get(colorIndex) & 0xFF,
                colorBuf.get(colorIndex + 1) & 0xFF,
                colorBuf.get(colorIndex + 2) & 0xFF,
                colorBuf.get(colorIndex + 3) & 0xFF);
    }

    record EncodedVertex(float x, float y, float u, float v, int r, int g, int b, int a) {
    }
}
