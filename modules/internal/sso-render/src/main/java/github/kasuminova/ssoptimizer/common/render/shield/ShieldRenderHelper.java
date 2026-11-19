package github.kasuminova.ssoptimizer.common.render.shield;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.render.EffectVertices;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.logging.Logger;

public final class ShieldRenderHelper {
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shield.enable";

    private static final Logger LOGGER = Logger.getLogger(ShieldRenderHelper.class.getName());

    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));

    private static final int GL_TEXTURE_2D           = 3553;
    private static final int GL_BLEND                = 3042;
    private static final int GL_SRC_ALPHA            = 770;
    private static final int GL_ONE                  = 1;
    private static final int GL_ONE_MINUS_SRC_ALPHA  = 771;

    private static ByteBuffer  colorBuf;
    private static FloatBuffer vertexBuf;
    private static FloatBuffer texCoordBuf;
    private static int         bufferVertexCapacity;
    private static float[]     fanUv        = new float[ShieldArcGeometry.INITIAL_MAX_SEGMENTS * 2];
    private static float[]     bandVertices = new float[ShieldArcGeometry.INITIAL_MAX_SEGMENTS * 4];

    static {
        LOGGER.info("[SSOptimizer] Shield render optimization enabled=" + ENABLED
                + ", algo=" + (ShieldArcGeometry.useRaycast() ? "raycast" : "recurrence")
                + " (toggle: -D" + ENABLE_PROPERTY + ", -D" + ShieldArcGeometry.ALGO_PROPERTY + ")");
    }

    private ShieldRenderHelper() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static final class Params {
        public float amount;
        public float arc;
        public float radius;
        public int segmentCount;
        public float[] segmentAlpha;
        public float segmentAlphaMax;
        public Color innerColor;
        public Color ringColor;
        public float ringAngle;
        public float innerAngle;
        public float textureScale;
        public boolean renderAdditive;
        public boolean shipFighter;
        public boolean shipFrigate;
        public float offsetX;
        public float offsetY;
        public float facing;
        public float chargeLevel;
        public float chargeDamageMult;
        public float effectBrightness;
        public float effectStrength;
        public float effectSizeMult;
        public float effectRadiusMult;
        public TextureObject innerTexture;
        public TextureObject bandTexture;
    }

    public static void render(final Params p) {
        SpriteBatch.getInstance().flushPending();
        float arcDeg = (p.arc + 10.0F) * p.chargeLevel;
        if (arcDeg <= 0.0F) {
            return;
        }

        if (ENABLED && !RenderThreadMode.isEnabled() && GL11.glGetInteger(GL11.GL_LIST_INDEX) == 0) {
            renderBatched(p, arcDeg);
        } else {
            renderImmediate(p, arcDeg);
        }
    }

    private static void renderBatched(final Params p, final float arcDeg) {
        int segmentCount = p.segmentCount;
        float damageMult = p.chargeDamageMult * p.amount;
        float startDeg = p.facing - arcDeg / 2.0F;

        ensureCapacity(segmentCount);

        p.innerTexture.bind();
        GL11.glEnable(GL_TEXTURE_2D);
        GL11.glEnable(GL_BLEND);
        GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);

        float[] perimeter = ShieldArcGeometry.fanVertices(arcDeg, segmentCount, p.radius);
        float uvShift = (float) Math.toRadians(arcDeg / 2.0F);
        int red = p.innerColor.getRed();
        int green = p.innerColor.getGreen();
        int blue = p.innerColor.getBlue();
        int colorAlpha = p.innerColor.getAlpha();

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();

        for (int pass = 0; pass < 2; pass++) {
            float phase = (pass == 1 ? -p.innerAngle : p.innerAngle) - uvShift;
            ShieldArcGeometry.fillFanTexCoords(fanUv, arcDeg, segmentCount, phase);

            for (int k = 0; k < segmentCount - 1; k++) {
                putFanCenter(red, green, blue);
                putFanVertex(perimeter, fanUv, p, k, arcDeg, damageMult, red, green, blue, colorAlpha);
                putFanVertex(perimeter, fanUv, p, k + 1, arcDeg, damageMult, red, green, blue, colorAlpha);
            }
        }

        flush(GL11.GL_TRIANGLES);
        GL11.glPopMatrix();

        renderBandBatched(p, arcDeg, damageMult, startDeg);
    }

    private static void renderBandBatched(final Params p, final float arcDeg,
                                          final float damageMult, final float startDeg) {
        int segmentCount = p.segmentCount;

        float bandWidth = 5.0F;
        if (p.shipFighter) {
            bandWidth = 3.0F;
        }
        if (p.shipFrigate) {
            bandWidth = 4.0F;
        }
        bandWidth = bandWidth + bandWidth * p.effectStrength * p.effectSizeMult * p.effectBrightness;

        float scaleEff = p.textureScale + p.textureScale * p.effectStrength * p.effectRadiusMult * p.effectBrightness;
        float bandArcRad = ShieldArcGeometry.bandArcRadians(startDeg, arcDeg);

        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);
        GL11.glEnable(GL_TEXTURE_2D);
        p.bandTexture.bind();
        GL11.glEnable(GL_BLEND);
        if (p.renderAdditive) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        ShieldArcGeometry.fillBandStrip(bandVertices, segmentCount, bandArcRad,
                p.radius, bandWidth, scaleEff, p.ringAngle);

        int red = p.ringColor.getRed();
        int green = p.ringColor.getGreen();
        int blue = p.ringColor.getBlue();

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();

        for (int k = 0; k < segmentCount; k++) {
            int alpha = ShieldArcGeometry.bandAlpha(k, segmentCount, bandArcRad,
                    damageMult, p.segmentAlpha[k], p.segmentAlphaMax);
            byte rb = (byte) red;
            byte gb = (byte) green;
            byte bb = (byte) blue;
            byte ab = (byte) alpha;
            int base = k * 4;

            colorBuf.put(rb).put(gb).put(bb).put(ab);
            texCoordBuf.put(0.0F).put(0.0F);
            vertexBuf.put(bandVertices[base]).put(bandVertices[base + 1]);

            colorBuf.put(rb).put(gb).put(bb).put(ab);
            texCoordBuf.put(0.0F).put(1.0F);
            vertexBuf.put(bandVertices[base + 2]).put(bandVertices[base + 3]);
        }

        flush(GL11.GL_QUAD_STRIP);
        GL11.glPopMatrix();
    }

    private static void renderImmediate(final Params p, final float arcDeg) {
        float damageMult = p.chargeDamageMult * p.amount;
        float startDeg = p.facing - arcDeg / 2.0F;

        p.innerTexture.bind();
        GL11.glEnable(GL_TEXTURE_2D);
        GL11.glEnable(GL_BLEND);
        GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);

        var fan = new Fan(arcDeg, p.radius, p.innerAngle, damageMult, p.segmentAlphaMax, p.innerColor);
        emit(p, fan.color(), fan::emit);

        GL11.glPopMatrix();
        renderBandImmediate(p, arcDeg, damageMult, startDeg);
    }

    private static void renderBandImmediate(final Params p, final float arcDeg,
                                            final float damageMult, final float startDeg) {
        float bandWidth = 5.0F;
        if (p.shipFighter) {
            bandWidth = 3.0F;
        }
        if (p.shipFrigate) {
            bandWidth = 4.0F;
        }
        bandWidth = bandWidth + bandWidth * p.effectStrength * p.effectSizeMult * p.effectBrightness;

        float bandArcRad = ShieldArcGeometry.bandArcRadians(startDeg, arcDeg);

        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);
        GL11.glEnable(GL_TEXTURE_2D);
        p.bandTexture.bind();
        GL11.glEnable(GL_BLEND);
        if (p.renderAdditive) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        float scale = p.textureScale + p.textureScale * p.effectStrength * p.effectRadiusMult * p.effectBrightness;
        var band = new Band(bandArcRad, p.radius, bandWidth, scale, p.ringAngle, damageMult,
                p.segmentAlphaMax, p.ringColor);
        emit(p, band.color(), band::emit);
        GL11.glPopMatrix();
    }

    @FunctionalInterface
    private interface Segments {
        void emit(EffectVertices out, float[] alpha, int count);
    }

    private static void emit(Params p, Color color, Segments geometry) {
        int count = p.segmentCount;
        if (ENABLED && count > 0 && GlDispatch.canSubmitOwnedDraw() && color != null && color.getClass() == Color.class) {
            float[] alpha = new float[count];
            System.arraycopy(p.segmentAlpha, 0, alpha, 0, count);
            EffectVertices.submit(out -> geometry.emit(out, alpha, count));
        } else {
            geometry.emit(EffectVertices.RECORDED, p.segmentAlpha, count);
        }
    }

    record Fan(float arc, float radius, float angle, float damage, float alphaMax, Color color) {
        void emit(EffectVertices out, float[] alpha, int count) {
            float delta = (float) Math.toRadians(arc) / (count - 1);
            float r = radius * 1.07F;
            for (int pass = 0; pass < 2; pass++) {
                float phase = (pass == 0 ? angle : -angle) - (float) Math.toRadians(arc / 2.0F);
                out.begin(GL11.GL_TRIANGLE_FAN);
                try {
                    setColor(out, color, 0);
                    out.uv(0.5F, 0.5F);
                    out.vertex(0.0F, 0.0F);
                    for (int k = 0; k < count; k++) {
                        setColor(out, color, ShieldArcGeometry.fanVertexAlpha(k, count, arc,
                                damage, alpha[k], alphaMax, color.getAlpha()));
                        float a = k * delta;
                        out.uv(0.5F + (float) Math.cos(a + phase) / 2.0F,
                                0.5F + (float) Math.sin(a + phase) / 2.0F);
                        out.vertex(r * (float) Math.cos(a), r * (float) Math.sin(a));
                    }
                } finally {
                    out.end();
                }
            }
        }
    }

    record Band(float arc, float radius, float width, float scale, float angle,
                float damage, float alphaMax, Color color) {
        void emit(EffectVertices out, float[] alpha, int count) {
            float delta = arc / (count - 1);
            out.begin(GL11.GL_QUAD_STRIP);
            try {
                for (int k = 0; k < count; k++) {
                    float r = radius + scale * (float) Math.sin(angle * 10.0F + k * delta * 10.0F);
                    setColor(out, color, ShieldArcGeometry.bandAlpha(k, count, arc, damage, alpha[k], alphaMax));
                    float c = (float) Math.cos(delta * k);
                    float s = (float) Math.sin(delta * k);
                    out.uv(0.0F, 0.0F);
                    out.vertex(c * r, s * r);
                    out.uv(0.0F, 1.0F);
                    out.vertex(c * (r - width), s * (r - width));
                }
            } finally {
                out.end();
            }
        }
    }

    private static void setColor(EffectVertices out, Color color, int alpha) {
        out.color((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) alpha);
    }

    private static void ensureCapacity(final int segmentCount) {
        int required = Math.max(2 * 3 * (segmentCount - 1), 2 * segmentCount);
        if (colorBuf != null && required <= bufferVertexCapacity) {
            return;
        }

        int newCapacity = Math.max(ShieldArcGeometry.INITIAL_MAX_SEGMENTS * 6, bufferVertexCapacity * 2);
        while (newCapacity < required) {
            newCapacity *= 2;
        }
        LOGGER.info("[SSOptimizer] Shield render buffers grow to " + newCapacity + " vertices (segmentCount="
                + segmentCount + ")");

        colorBuf = ByteBuffer.allocateDirect(newCapacity * 4).order(ByteOrder.nativeOrder());
        vertexBuf = ByteBuffer.allocateDirect(newCapacity * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuf = ByteBuffer.allocateDirect(newCapacity * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        bufferVertexCapacity = newCapacity;

        if (fanUv.length < segmentCount * 2) {
            fanUv = new float[segmentCount * 2];
        }
        if (bandVertices.length < segmentCount * 4) {
            bandVertices = new float[segmentCount * 4];
        }
    }

    private static void putFanCenter(final int red, final int green, final int blue) {
        colorBuf.put((byte) red).put((byte) green).put((byte) blue).put((byte) 0);
        texCoordBuf.put(0.5F).put(0.5F);
        vertexBuf.put(0.0F).put(0.0F);
    }

    private static void putFanVertex(final float[] perimeter, final float[] uv, final Params p, final int k,
                                     final float arcDeg, final float damageMult,
                                     final int red, final int green, final int blue, final int colorAlpha) {
        int alpha = ShieldArcGeometry.fanVertexAlpha(k, p.segmentCount, arcDeg,
                damageMult, p.segmentAlpha[k], p.segmentAlphaMax, colorAlpha);
        colorBuf.put((byte) red).put((byte) green).put((byte) blue).put((byte) alpha);
        texCoordBuf.put(uv[k * 2]).put(uv[k * 2 + 1]);
        vertexBuf.put(perimeter[k * 2]).put(perimeter[k * 2 + 1]);
    }

    private static void flush(final int mode) {
        int numVertices = vertexBuf.position() / 2;
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

            GL11.glDrawArrays(mode, 0, numVertices);
        } finally {
            GL11.glPopClientAttrib();
        }

        GL11.glColor4ub(finalRed, finalGreen, finalBlue, finalAlpha);
    }
}
