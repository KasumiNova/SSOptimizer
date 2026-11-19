package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.bridge.opengl.ParticleBatchGl;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleParameters.Kind;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleParameters.Vertices;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class ParticleBatchHelper {
    static final int MAX_PARTICLES = 16384;
    private static final ThreadLocal<Batch> SMOOTH = local(Kind.SMOOTH);
    private static final ThreadLocal<Batch> NEGATIVE = local(Kind.NEGATIVE);
    private static final ThreadLocal<Batch> SMOKE = local(Kind.SMOKE);
    private static final ThreadLocal<Batch> GENERIC = local(Kind.GENERIC);
    private static final ThreadLocal<Batch> NEBULA = local(Kind.NEBULA);

    private ParticleBatchHelper() {
    }

    private static ThreadLocal<Batch> local(Kind kind) {
        return ThreadLocal.withInitial(() -> new Batch(kind));
    }

    public static Color adjustBrightness(Color color, float brightness) {
        int rgba = brightnessRgba(color, brightness);
        return new Color(Integer.rotateRight(rgba, 8), true);
    }

    public static int brightnessRgba(Color color, float brightness) {
        if (color == null || !Float.isFinite(brightness) || brightness <= 0) {
            return 0;
        }
        return ParticleParameters.rgba(scale(color.getRed(), brightness), scale(color.getGreen(), brightness),
                scale(color.getBlue(), brightness), scale(color.getAlpha(), brightness));
    }

    private static int scale(int component, float brightness) {
        return Math.max(0, Math.min(255, Math.round(component * brightness)));
    }

    public static void beginSmoothBatch() { SMOOTH.get().begin(); }
    public static void beginNegativeBatch() { NEGATIVE.get().begin(); }
    public static void beginSmokeBatch() { SMOKE.get().begin(); }
    public static void beginGenericTextureBatch() { GENERIC.get().begin(); }
    public static void beginNebulaBatch() { NEBULA.get().begin(); }

    public static void addSmoothParticle(int r, int g, int b, int a, float x, float y,
                                         float offsetX, float offsetY, float size) {
        addSmoothParticle(ParticleParameters.rgba(r, g, b, a), x, y, offsetX, offsetY, size);
    }

    public static void addSmoothParticle(int color, float x, float y, float offsetX, float offsetY, float size) {
        SMOOTH.get().writable().axis(color, x + offsetX, y + offsetY, size);
    }

    public static void addNegativeParticle(int r, int g, int b, int a, float x, float y,
                                           float offsetX, float offsetY, float size) {
        NEGATIVE.get().writable().axis(ParticleParameters.rgba(r, g, b, a), x + offsetX, y + offsetY, size);
    }

    public static void addSmokeParticle(int r, int g, int b, int a, float x, float y, float angle,
                                        float offsetX, float offsetY, float size) {
        SMOKE.get().writable().smoke(ParticleParameters.rgba(r, g, b, a), x, y, angle, offsetX, offsetY, size);
    }

    public static void addGenericTextureParticle(int r, int g, int b, int a, int blendSrc, int blendDst,
                                                 float x, float y, float angle, float offsetX, float offsetY,
                                                 float width, float height, float tw, float th, int renderCount) {
        Batch batch = GENERIC.get();
        batch.blend(blendSrc, blendDst);
        int color = ParticleParameters.rgba(r, g, b, a);
        for (int first = 0; first < renderCount;) {
            ParticleParameters params = batch.writable();
            int count = Math.min(renderCount - first, MAX_PARTICLES - params.quadCount());
            params.generic(color, x, y, angle, offsetX, offsetY, width, height, tw, th, first, count);
            first += count;
        }
    }

    public static void addNebulaParticle(int r, int g, int b, int a, int blendSrc, int blendDst,
                                         float x, float y, float angle, float offsetX, float offsetY,
                                         float width, float height, float u0, float v0, float u1, float v1) {
        Batch batch = NEBULA.get();
        batch.blend(blendSrc, blendDst);
        batch.writable().nebula(ParticleParameters.rgba(r, g, b, a), x, y, angle, offsetX, offsetY,
                width, height, u0, v0, u1, v1);
    }

    public static void flushSmoothBatch() { SMOOTH.get().flush(); }
    public static void flushNegativeBatch() { NEGATIVE.get().flush(); }
    public static void flushSmokeBatch() { SMOKE.get().flush(); }
    public static void flushGenericTextureBatch() { GENERIC.get().flush(); }
    public static void flushNebulaBatch() { NEBULA.get().flush(); }

    private static final class Batch {
        private final Kind kind;
        private ParticleParameters pending;
        private int src = -1;
        private int dst = -1;

        Batch(Kind kind) {
            this.kind = kind;
        }

        void begin() {
            if (pending != null) {
                pending.release();
                pending = null;
            }
            src = dst = -1;
        }

        void blend(int newSrc, int newDst) {
            if (newSrc == src && newDst == dst) {
                return;
            }
            flush();
            GL11.glBlendFunc(newSrc, newDst);
            src = newSrc;
            dst = newDst;
        }

        ParticleParameters writable() {
            if (pending != null && pending.quadCount() == MAX_PARTICLES) {
                flush();
            }
            if (pending == null) {
                pending = kind.acquire();
            }
            return pending;
        }

        void flush() {
            if (pending == null) {
                return;
            }
            boolean queued = GlDispatch.canSubmitOwnedDraw();
            ParticleParameters params = pending;
            pending = null;
            if (queued) {
                ParticleBatchGl.submit(params);
            } else {
                try {
                    drawOnCallingThread(params);
                } finally {
                    params.release();
                }
            }
        }
    }

    private static void drawOnCallingThread(ParticleParameters params) {
        Vertices vertices = params.expand();
        ByteBuffer colors = vertices.colors();
        FloatBuffer positions = vertices.positions(), uv = vertices.texCoords();
        int numVertices = positions.remaining() / 2;
        if (params.smooth()) {
            if (SpriteRenderHelper.isNativeLoaded()) {
                nativeRenderImmediateQuads(colors, positions, uv, numVertices);
                return;
            }
            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < numVertices; i++) {
                GL11.glColor4ub(colors.get(i * 4), colors.get(i * 4 + 1), colors.get(i * 4 + 2), colors.get(i * 4 + 3));
                GL11.glTexCoord2f(uv.get(i * 2), uv.get(i * 2 + 1));
                GL11.glVertex2f(positions.get(i * 2), positions.get(i * 2 + 1));
            }
            GL11.glEnd();
            return;
        }
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glColorPointer(4, true, 0, colors);
            GL11.glVertexPointer(2, 0, positions);
            GL11.glTexCoordPointer(2, 0, uv);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, numVertices);
        } finally {
            GL11.glPopClientAttrib();
        }
        int last = colors.limit() - 4;
        GL11.glColor4ub(colors.get(last), colors.get(last + 1), colors.get(last + 2), colors.get(last + 3));
    }

    static native void nativeRenderImmediateQuads(ByteBuffer colors, FloatBuffer positions,
                                                 FloatBuffer texCoords, int numVertices);
}
