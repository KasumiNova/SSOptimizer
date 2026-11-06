package github.kasuminova.ssoptimizer.common.render.engine;

import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class ParticleBatchHelper {
    static final         int MAX_PARTICLES = 16384;
    private static final int MAX_VERTICES  = MAX_PARTICLES * 4;

    private static final BatchContext SMOOTH_BATCH          = new BatchContext();
    private static final BatchContext SMOKE_BATCH           = new BatchContext();
    private static final BatchContext GENERIC_TEXTURE_BATCH = new BatchContext();
    private static final BatchContext NEBULA_BATCH          = new BatchContext();
    private static final BatchContext NEGATIVE_BATCH        = new BatchContext();

    private ParticleBatchHelper() {
    }

    public static Color adjustBrightness(final Color color,
                                         final float brightness) {
        if (color == null) {
            return new Color(0, 0, 0, 0);
        }

        final float normalizedBrightness = Float.isFinite(brightness) ? Math.max(0.0f, brightness) : 0.0f;
        return new Color(
                scaleColorComponent(color.getRed(), normalizedBrightness),
                scaleColorComponent(color.getGreen(), normalizedBrightness),
                scaleColorComponent(color.getBlue(), normalizedBrightness),
                scaleColorComponent(color.getAlpha(), normalizedBrightness)
        );
    }

    public static void beginSmoothBatch() {
        beginBatch(SMOOTH_BATCH, false);
    }

    public static void beginSmokeBatch() {
        beginBatch(SMOKE_BATCH, false);
    }

    public static void beginGenericTextureBatch() {
        beginBatch(GENERIC_TEXTURE_BATCH, true);
    }

    public static void beginNebulaBatch() {
        beginBatch(NEBULA_BATCH, true);
    }

    public static void beginNegativeBatch() {
        beginBatch(NEGATIVE_BATCH, false);
    }

    public static void addSmoothParticle(
            int r, int g, int b, int a,
            float x, float y,
            float offsetX, float offsetY, float size) {
        BatchContext batch = SMOOTH_BATCH;
        if (batch.numVertices + 4 > MAX_VERTICES) {
            flushSmoothBatch();
        }

        putAxisAlignedQuad(batch, (byte) r, (byte) g, (byte) b, (byte) a, x, y, offsetX, offsetY, size);
    }

    public static void addNegativeParticle(
            int r, int g, int b, int a,
            float x, float y,
            float offsetX, float offsetY, float size) {
        BatchContext batch = NEGATIVE_BATCH;
        if (batch.numVertices + 4 > MAX_VERTICES) {
            flushNegativeBatch();
        }

        putAxisAlignedQuad(batch, (byte) r, (byte) g, (byte) b, (byte) a, x, y, offsetX, offsetY, size);
    }

    public static void addSmokeParticle(
            int r, int g, int b, int a,
            float x, float y, float angle,
            float offsetX, float offsetY, float size) {
        BatchContext batch = SMOKE_BATCH;
        if (batch.numVertices + 4 > MAX_VERTICES) {
            flushSmokeBatch();
        }

        final byte rb = (byte) r, gb = (byte) g, bb = (byte) b, ab = (byte) a;
        for (int i = 0; i < 4; i++) {
            batch.colorBuf.put(rb).put(gb).put(bb).put(ab);
        }

        batch.texCoordBuf.put(0).put(0);
        batch.texCoordBuf.put(0).put(1);
        batch.texCoordBuf.put(1).put(1);
        batch.texCoordBuf.put(1).put(0);

        final float rad = (float) Math.toRadians(angle);
        final float cosA = (float) Math.cos(rad);
        final float sinA = (float) Math.sin(rad);

        final float ox1 = offsetX + size;
        final float oy1 = offsetY + size;

        batch.vertexBuf.put(x + offsetX * cosA - offsetY * sinA);
        batch.vertexBuf.put(y + offsetX * sinA + offsetY * cosA);

        batch.vertexBuf.put(x + offsetX * cosA - oy1 * sinA);
        batch.vertexBuf.put(y + offsetX * sinA + oy1 * cosA);

        batch.vertexBuf.put(x + ox1 * cosA - oy1 * sinA);
        batch.vertexBuf.put(y + ox1 * sinA + oy1 * cosA);

        batch.vertexBuf.put(x + ox1 * cosA - offsetY * sinA);
        batch.vertexBuf.put(y + ox1 * sinA + offsetY * cosA);

        batch.numVertices += 4;
    }

    public static void addGenericTextureParticle(
            int r, int g, int b, int a,
            int blendSrc, int blendDst,
            float x, float y, float angle,
            float offsetX, float offsetY,
            float width, float height,
            float tw, float th,
            int renderCount) {
        BatchContext batch = GENERIC_TEXTURE_BATCH;

        if (blendSrc != batch.currentBlendSrc || blendDst != batch.currentBlendDst) {
            if (batch.numVertices > 0) {
                flushGenericTextureBatch();
            }
            GL11.glBlendFunc(blendSrc, blendDst);
            batch.currentBlendSrc = blendSrc;
            batch.currentBlendDst = blendDst;
        }

        final float rad = (float) Math.toRadians(angle);
        final float cosA = (float) Math.cos(rad);
        final float sinA = (float) Math.sin(rad);
        final byte rb = (byte) r, gb = (byte) g, bb = (byte) b, ab = (byte) a;

        for (int i = 0; i < renderCount; i++) {
            if (batch.numVertices + 4 > MAX_VERTICES) {
                flushGenericTextureBatch();
            }

            putRotatedQuad(batch, rb, gb, bb, ab, x, y, cosA, sinA,
                    offsetX + i, offsetY, width, height, 0, 0, tw, th);
        }
    }

    public static void addNebulaParticle(
            int r, int g, int b, int a,
            int blendSrc, int blendDst,
            float x, float y, float angle,
            float offsetX, float offsetY,
            float width, float height,
            float u0, float v0, float u1, float v1) {
        BatchContext batch = NEBULA_BATCH;

        if (blendSrc != batch.currentBlendSrc || blendDst != batch.currentBlendDst) {
            if (batch.numVertices > 0) {
                flushNebulaBatch();
            }
            GL11.glBlendFunc(blendSrc, blendDst);
            batch.currentBlendSrc = blendSrc;
            batch.currentBlendDst = blendDst;
        }

        if (batch.numVertices + 4 > MAX_VERTICES) {
            flushNebulaBatch();
        }

        final float rad = (float) Math.toRadians(angle);
        putRotatedQuad(batch, (byte) r, (byte) g, (byte) b, (byte) a, x, y,
                (float) Math.cos(rad), (float) Math.sin(rad),
                offsetX, offsetY, width, height, u0, v0, u1, v1);
    }

    public static void flushSmoothBatch() {
        flushImmediateBatch(SMOOTH_BATCH);
    }

    public static void flushSmokeBatch() {
        flushArrayBatch(SMOKE_BATCH);
    }

    public static void flushGenericTextureBatch() {
        flushArrayBatch(GENERIC_TEXTURE_BATCH);
    }

    public static void flushNebulaBatch() {
        flushArrayBatch(NEBULA_BATCH);
    }

    public static void flushNegativeBatch() {
        flushArrayBatch(NEGATIVE_BATCH);
    }

    private static int scaleColorComponent(final int component,
                                           final float brightness) {
        return clampColorComponent(Math.round(component * brightness));
    }

    private static void putAxisAlignedQuad(BatchContext batch, byte rb, byte gb, byte bb, byte ab,
                                           float x, float y, float offsetX, float offsetY, float size) {
        for (int i = 0; i < 4; i++) {
            batch.colorBuf.put(rb).put(gb).put(bb).put(ab);
        }

        batch.texCoordBuf.put(0).put(0);
        batch.texCoordBuf.put(0).put(1);
        batch.texCoordBuf.put(1).put(1);
        batch.texCoordBuf.put(1).put(0);

        final float vx = x + offsetX;
        final float vy = y + offsetY;
        batch.vertexBuf.put(vx).put(vy);
        batch.vertexBuf.put(vx).put(vy + size);
        batch.vertexBuf.put(vx + size).put(vy + size);
        batch.vertexBuf.put(vx + size).put(vy);

        batch.numVertices += 4;
    }

    private static void putRotatedQuad(BatchContext batch, byte rb, byte gb, byte bb, byte ab,
                                       float x, float y, float cosA, float sinA,
                                       float offsetX, float offsetY, float width, float height,
                                       float u0, float v0, float u1, float v1) {
        final float lx0 = offsetX;
        final float ly0 = offsetY;
        final float lx1 = offsetX;
        final float ly1 = offsetY + height;
        final float lx2 = offsetX + width;
        final float ly2 = offsetY + height;
        final float lx3 = offsetX + width;
        final float ly3 = offsetY;

        batch.vertexBuf.put(x + lx0 * cosA - ly0 * sinA);
        batch.vertexBuf.put(y + lx0 * sinA + ly0 * cosA);
        batch.vertexBuf.put(x + lx1 * cosA - ly1 * sinA);
        batch.vertexBuf.put(y + lx1 * sinA + ly1 * cosA);
        batch.vertexBuf.put(x + lx2 * cosA - ly2 * sinA);
        batch.vertexBuf.put(y + lx2 * sinA + ly2 * cosA);
        batch.vertexBuf.put(x + lx3 * cosA - ly3 * sinA);
        batch.vertexBuf.put(y + lx3 * sinA + ly3 * cosA);

        batch.colorBuf.put(rb).put(gb).put(bb).put(ab);
        batch.colorBuf.put(rb).put(gb).put(bb).put(ab);
        batch.colorBuf.put(rb).put(gb).put(bb).put(ab);
        batch.colorBuf.put(rb).put(gb).put(bb).put(ab);

        batch.texCoordBuf.put(u0).put(v0);
        batch.texCoordBuf.put(u0).put(v1);
        batch.texCoordBuf.put(u1).put(v1);
        batch.texCoordBuf.put(u1).put(v0);

        batch.numVertices += 4;
    }

    private static int clampColorComponent(final int component) {
        return Math.max(0, Math.min(255, component));
    }

    private static void beginBatch(BatchContext batch, boolean resetBlendTracking) {
        ensureBuffers(batch);
        batch.colorBuf.clear();
        batch.vertexBuf.clear();
        batch.texCoordBuf.clear();
        batch.numVertices = 0;
        if (resetBlendTracking) {
            batch.currentBlendSrc = -1;
            batch.currentBlendDst = -1;
        }
    }

    private static void ensureBuffers(BatchContext batch) {
        if (batch.colorBuf != null) {
            return;
        }

        batch.colorBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 4)
                                   .order(ByteOrder.nativeOrder());
        batch.vertexBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 2 * 4)
                                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        batch.texCoordBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 2 * 4)
                                      .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static void flushImmediateBatch(BatchContext batch) {
        if (batch.numVertices == 0) {
            return;
        }

        batch.colorBuf.flip();
        batch.vertexBuf.flip();
        batch.texCoordBuf.flip();

        if (SpriteRenderHelper.isNativeLoaded()) {
            nativeRenderImmediateQuads(batch.colorBuf, batch.vertexBuf, batch.texCoordBuf, batch.numVertices);
        } else {
            renderImmediateQuads(batch.colorBuf, batch.vertexBuf, batch.texCoordBuf, batch.numVertices);
        }

        batch.colorBuf.clear();
        batch.vertexBuf.clear();
        batch.texCoordBuf.clear();
        batch.numVertices = 0;
    }

    private static void renderImmediateQuads(ByteBuffer colorBuf, FloatBuffer vertexBuf,
                                             FloatBuffer texCoordBuf, int numVertices) {
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < numVertices; i++) {
            int colorIndex = i * 4;
            int coordIndex = i * 2;

            GL11.glColor4ub(colorBuf.get(colorIndex), colorBuf.get(colorIndex + 1),
                    colorBuf.get(colorIndex + 2), colorBuf.get(colorIndex + 3));
            GL11.glTexCoord2f(texCoordBuf.get(coordIndex), texCoordBuf.get(coordIndex + 1));
            GL11.glVertex2f(vertexBuf.get(coordIndex), vertexBuf.get(coordIndex + 1));
        }
        GL11.glEnd();
    }

    private static void flushArrayBatch(BatchContext batch) {
        if (batch.numVertices == 0) {
            return;
        }

        batch.colorBuf.flip();
        batch.vertexBuf.flip();
        batch.texCoordBuf.flip();

        int finalColorIndex = (batch.numVertices - 1) * 4;
        byte finalRed = batch.colorBuf.get(finalColorIndex);
        byte finalGreen = batch.colorBuf.get(finalColorIndex + 1);
        byte finalBlue = batch.colorBuf.get(finalColorIndex + 2);
        byte finalAlpha = batch.colorBuf.get(finalColorIndex + 3);

        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            GL11.glColorPointer(4, true, 0, batch.colorBuf);
            GL11.glVertexPointer(2, 0, batch.vertexBuf);
            GL11.glTexCoordPointer(2, 0, batch.texCoordBuf);

            GL11.glDrawArrays(GL11.GL_QUADS, 0, batch.numVertices);
        } finally {
            GL11.glPopClientAttrib();
        }

        GL11.glColor4ub(finalRed, finalGreen, finalBlue, finalAlpha);

        batch.colorBuf.clear();
        batch.vertexBuf.clear();
        batch.texCoordBuf.clear();
        batch.numVertices = 0;
    }

    static int getNumVertices() {
        return SMOOTH_BATCH.numVertices + SMOKE_BATCH.numVertices + GENERIC_TEXTURE_BATCH.numVertices
                + NEBULA_BATCH.numVertices + NEGATIVE_BATCH.numVertices;
    }

    private static native void nativeRenderImmediateQuads(
            ByteBuffer colorBuf,
            FloatBuffer vertexBuf,
            FloatBuffer texCoordBuf,
            int numVertices);

    private static final class BatchContext {
        private ByteBuffer  colorBuf;
        private FloatBuffer vertexBuf;
        private FloatBuffer texCoordBuf;
        private int         numVertices;
        private int         currentBlendSrc = -1;
        private int         currentBlendDst = -1;
    }
}
