package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.bridge.opengl.CampaignArrayDraw;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;

public final class CampaignContrailBatchHelper {
    private static final int   MAX_VERTICES           = 262_144;
    private static final float V_MIN                  = 0.01f;
    private static final float V_MAX                  = 0.99f;
    private static final int   GL_SRC_ALPHA           = 770;
    private static final int   GL_ONE                 = 1;
    private static final int   GL_ONE_MINUS_SRC_ALPHA = 771;
    private static final float FULL_BRIGHTNESS_LENGTH = 50.0f;

    private static final ThreadLocal<DirectPairs> DIRECT = ThreadLocal.withInitial(DirectPairs::new);

    private CampaignContrailBatchHelper() {
    }

    public static void renderContrails(Map<Object, ContrailEngineV2.Contrail> contrails, float alphaMult) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        if (contrails.isEmpty()) {
            return;
        }

        Pairs output;
        if (GlDispatch.canSubmitOwnedDraw()) {
            output = new QueuedPairs();
        } else {
            DirectPairs direct = DIRECT.get();
            direct.beginStrip();
            output = direct;
        }
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            ContrailEngineV2.ContrailPoint fadeSource = null;
            Object activeBlend = new Object();
            boolean textureBound = false;
            for (ContrailEngineV2.Contrail contrail : contrails.values()) {
                EngineSlot.BlendMode blend = contrail.blendMode;
                if (activeBlend != blend) {
                    output.flush();
                    applyBlendMode(blend);
                    activeBlend = blend;
                }

                if (contrail.points.size() > 1) {
                    if (!textureBound) {
                        contrail.tex.bind();
                        textureBound = true;
                    }
                    fadeSource = encodeContrail(contrail, alphaMult, fadeSource, output);
                } else {
                    for (ContrailEngineV2.ContrailPoint point : contrail.points) {
                        point.maxBrightness = 0.0f;
                    }
                }
            }

            output.flush();
            output.restoreCurrentColor();
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    static ContrailEngineV2.ContrailPoint encodeContrail(ContrailEngineV2.Contrail contrail,
                                                         float alphaMult,
                                                         ContrailEngineV2.ContrailPoint fadeSource) {
        return encodeContrail(contrail, alphaMult, fadeSource, DIRECT.get());
    }

    private static ContrailEngineV2.ContrailPoint encodeContrail(ContrailEngineV2.Contrail contrail,
            float alphaMult, ContrailEngineV2.ContrailPoint fadeSource, Pairs output) {
        Color color = contrail.color;
        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        int baseAlpha = color.getAlpha();

        output.join();

        List<ContrailEngineV2.ContrailPoint> points = contrail.points;
        int size = points.size();
        ContrailEngineV2.ContrailPoint prev = null;
        for (int i = 0; i < size; i++) {
            ContrailEngineV2.ContrailPoint point = points.get(i);

            // 已 fadeOut 且亮度为零时 alpha 必为零，但仍须写回 fadeSource 与 lastProximityMult。
            if (point.fadeOut && point.maxBrightness == 0.0f) {
                fadeSource = point;
                float mult = 0.0f;
                if (mult > point.lastProximityMult) {
                    mult = point.lastProximityMult;
                }
                point.lastProximityMult = mult;

                float halfWidth = point.width / 2.0f;
                output.pair(red, green, blue, 0, point.texCoord, point.point.x, point.point.y,
                        point.perp.x, point.perp.y, halfWidth);
                prev = point;
                continue;
            }

            ContrailEngineV2.ContrailPoint next = i + 1 < size ? points.get(i + 1) : null;

            float brightness = 0.0f;
            float fadeWindow = 0.1f;
            float elapsed = point.elapsed;
            if (elapsed > point.elapsedWhenFadeOut) {
                elapsed = point.elapsedWhenFadeOut;
            }
            if (fadeWindow > point.duration / 2.0f) {
                fadeWindow = point.duration / 2.0f;
            }
            if (elapsed < fadeWindow) {
                brightness = elapsed / fadeWindow;
            } else {
                brightness = (point.duration - elapsed) / (point.duration - fadeWindow);
            }
            if (brightness > 1.0f) {
                brightness = 1.0f;
            }
            brightness *= alphaMult;

            if (next != null) {
                float aMinX = 0.0f;
                float aMinY = 0.0f;
                float aMaxX = 0.0f;
                float aMaxY = 0.0f;
                boolean curExpanded = false;

                float ndx = next.point.x - point.point.x;
                float ndy = next.point.y - point.point.y;
                float nextRadSum = (point.width + next.width) * 0.75f;
                boolean nextIntersects = false;
                if (ndx * ndx + ndy * ndy <= nextRadSum * nextRadSum) {
                    float halfExt = point.width / 2.0f * 1.5f;
                    aMinX = point.point.x - point.perp.x * halfExt;
                    aMinY = point.point.y - point.perp.y * halfExt;
                    aMaxX = point.point.x + point.perp.x * halfExt;
                    aMaxY = point.point.y + point.perp.y * halfExt;
                    curExpanded = true;

                    float nextExt = next.width / 2.0f * 1.5f;
                    float bMinX = next.point.x - next.perp.x * nextExt;
                    float bMinY = next.point.y - next.perp.y * nextExt;
                    float bMaxX = next.point.x + next.perp.x * nextExt;
                    float bMaxY = next.point.y + next.perp.y * nextExt;

                    nextIntersects = segmentsIntersect(aMinX, aMinY, aMaxX, aMaxY, bMinX, bMinY, bMaxX, bMaxY);
                }
                if (nextIntersects) {
                    markFadeOut(point);
                } else if (prev != null) {
                    float pdx = prev.point.x - point.point.x;
                    float pdy = prev.point.y - point.point.y;
                    float prevRadSum = (point.width + prev.width) * 0.75f;
                    if (pdx * pdx + pdy * pdy <= prevRadSum * prevRadSum) {
                        if (!curExpanded) {
                            float halfExt = point.width / 2.0f * 1.5f;
                            aMinX = point.point.x - point.perp.x * halfExt;
                            aMinY = point.point.y - point.perp.y * halfExt;
                            aMaxX = point.point.x + point.perp.x * halfExt;
                            aMaxY = point.point.y + point.perp.y * halfExt;
                        }

                        float prevExt = prev.width / 2.0f * 1.5f;
                        float cMinX = prev.point.x - prev.perp.x * prevExt;
                        float cMinY = prev.point.y - prev.perp.y * prevExt;
                        float cMaxX = prev.point.x + prev.perp.x * prevExt;
                        float cMaxY = prev.point.y + prev.perp.y * prevExt;

                        if (segmentsIntersect(aMinX, aMinY, aMaxX, aMaxY, cMinX, cMinY, cMaxX, cMaxY)) {
                            markFadeOut(point);
                        }
                    }

                    if (brightness > 0.0f) {
                        float toPrevX = prev.point.x - point.point.x;
                        float toPrevY = prev.point.y - point.point.y;
                        float toNextX = next.point.x - point.point.x;
                        float toNextY = next.point.y - point.point.y;
                        if (toPrevX * toNextX + toPrevY * toNextY > 0.0f) {
                            markFadeOut(point);
                        }
                    }
                }
            }

            if (i == 0) {
                brightness = 0.0f;
            }

            if (point.fadeOut) {
                fadeSource = point;
            }
            if (fadeSource != null) {
                float dx = point.point.x - fadeSource.point.x;
                float dy = point.point.y - fadeSource.point.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float fadeRatio = 1.0f;
                if (fadeSource.origMax > 0.0f) {
                    fadeRatio = 1.0f - fadeSource.maxBrightness / fadeSource.origMax;
                }
                float mult = 1.0f - fadeRatio + fadeRatio * Math.min(1.0f, dist / FULL_BRIGHTNESS_LENGTH);
                if (mult > point.lastProximityMult) {
                    mult = point.lastProximityMult;
                }
                point.lastProximityMult = mult;
                brightness *= mult;
            }

            int alpha = (int) (baseAlpha * point.maxBrightness * brightness);
            float halfWidth = point.width / 2.0f;

            output.pair(red, green, blue, alpha, point.texCoord, point.point.x, point.point.y,
                    point.perp.x, point.perp.y, halfWidth);
            prev = point;
        }

        ContrailEngineV2.ContrailPoint last = points.get(size - 1);
        Vector2f lastPoint = contrail.lastPoint;
        if (lastPoint != null) {
            float quarterWidth = last.width / 4.0f;
            output.pair(red, green, blue, 0, last.texCoord, lastPoint.x, lastPoint.y,
                    last.perp.x, last.perp.y, quarterWidth);
        }
        return fadeSource;
    }

    private static void markFadeOut(ContrailEngineV2.ContrailPoint point) {
        if (!point.fadeOut) {
            point.origMax = point.maxBrightness;
            point.elapsedWhenFadeOut = point.elapsed;
        }
        point.fadeOut = true;
    }

    static boolean segmentsIntersect(float ax, float ay, float bx, float by,
                                     float cx, float cy, float dx, float dy) {
        float denom = (dy - cy) * (bx - ax) - (dx - cx) * (by - ay);
        float ua = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx);
        float ub = (bx - ax) * (ay - cy) - (by - ay) * (ax - cx);
        if (denom != 0.0f || ua == 0.0f && ub == 0.0f) {
            if (denom == 0.0f && ua == 0.0f && ub == 0.0f) {
                float minX;
                float maxX;
                if (ax < bx) {
                    minX = ax;
                    maxX = bx;
                } else {
                    minX = bx;
                    maxX = ax;
                }
                float minY;
                float maxY;
                if (ay < by) {
                    minY = ay;
                    maxY = by;
                } else {
                    minY = by;
                    maxY = ay;
                }
                if (cx >= minX && cx <= maxX && cy >= minY && cy <= maxY) {
                    return true;
                }
                return dx >= minX && dx <= maxX && dy >= minY && dy <= maxY;
            }
            float t = ua / denom;
            float u = ub / denom;
            return t >= 0.0f && t <= 1.0f && u >= 0.0f && u <= 1.0f;
        }
        return false;
    }

    private static void applyBlendMode(EngineSlot.BlendMode blendMode) {
        if (blendMode == EngineSlot.BlendMode.GLOW) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    static void beginStrip() { DIRECT.get().beginStrip(); }
    static int getNumVertices() { return DIRECT.get().numVertices; }
    static EncodedVertex vertexAt(int index) { return DIRECT.get().vertexAt(index); }

    private interface Pairs {
        void pair(int r, int g, int b, int a, float u, float x, float y, float nx, float ny, float halfWidth);
        void join();
        void flush();
        void restoreCurrentColor();
    }

    private static final class QueuedPairs implements Pairs {
        private CampaignTrailPairs pending;
        private boolean hasColor;
        private int lastColor;

        public void pair(int r, int g, int b, int a, float u, float x, float y, float nx, float ny, float halfWidth) {
            if (pending != null && pending.size() == MAX_VERTICES / 2) flush();
            if (pending == null) pending = new CampaignTrailPairs();
            lastColor = r << 24 | g << 16 | b << 8 | a & 255;
            hasColor = true;
            pending.add(x, y, nx, ny, halfWidth, u, lastColor);
        }

        public void join() {
            if (pending == null) return;
            if (pending.size() == MAX_VERTICES / 2) flush();
            else pending.join();
        }

        public void flush() {
            if (pending == null) return;
            CampaignTrailPairs batch = pending;
            pending = null;
            if (GlDispatch.canSubmitOwnedDraw()) {
                CampaignArrayDraw.submit(batch);
            } else {
                // 纹理绑定或颜色回调可能在收集期间改变 display list 状态。
                DirectPairs direct = DIRECT.get();
                direct.beginStrip();
                batch.expand(direct::appendVertex);
                direct.flush();
            }
        }

        public void restoreCurrentColor() {
            if (hasColor) GL11.glColor4ub((byte) (lastColor >>> 24), (byte) (lastColor >>> 16),
                    (byte) (lastColor >>> 8), (byte) lastColor);
        }
    }

    private static final class DirectPairs implements Pairs {
        private ByteBuffer  colorBuf;
        private FloatBuffer vertexBuf;
        private FloatBuffer texCoordBuf;
        private int         numVertices;

        private boolean hasFlushedStrip;
        private byte finalR;
        private byte finalG;
        private byte finalB;
        private byte finalA;
        private float lastU;
        private float lastX;
        private float lastY;
        private byte  lastR;
        private byte  lastG;
        private byte  lastB;
        private byte  lastA;

        void beginStrip() {
            ensureBuffers();
            colorBuf.clear();
            vertexBuf.clear();
            texCoordBuf.clear();
            numVertices = 0;
            hasFlushedStrip = false;
        }

        private void ensureBuffers() {
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

        public void join() {
            if (numVertices == 0) {
                return;
            }
            if (numVertices + 2 > MAX_VERTICES) {
                flush();
                return;
            }
            putVertex(lastU, lastX, lastY, lastR, lastG, lastB, lastA);
            putVertex(lastU, lastX, lastY, lastR, lastG, lastB, lastA);
        }

        private void appendVertex(float x, float y, float u, float v, int rgba) {
            colorBuf.put((byte) (rgba >>> 24)).put((byte) (rgba >>> 16)).put((byte) (rgba >>> 8)).put((byte) rgba);
            texCoordBuf.put(u).put(v);
            vertexBuf.put(x).put(y);
            numVertices++;
        }

        private void putVertex(float u, float x, float y, byte r, byte g, byte b, byte a) {
            colorBuf.put(r).put(g).put(b).put(a);
            texCoordBuf.put(u).put(V_MAX);
            vertexBuf.put(x).put(y);
            numVertices++;
        }

        public void pair(int r, int g, int b, int a, float u, float x, float y,
                         float nx, float ny, float halfWidth) {
            float leftX = x - nx * halfWidth, leftY = y - ny * halfWidth;
            float rightX = x + nx * halfWidth, rightY = y + ny * halfWidth;
            if (numVertices + 2 > MAX_VERTICES) {
                flush();
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

        public void flush() {
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

        public void restoreCurrentColor() {
            if (!hasFlushedStrip) {
                return;
            }
            GL11.glColor4ub(finalR, finalG, finalB, finalA);
        }

        EncodedVertex vertexAt(int index) {
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

    }

    record EncodedVertex(float x, float y, float u, float v, int r, int g, int b, int a) {
    }
}
