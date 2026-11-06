package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.CampaignFleetMemberView;
import com.fs.starfarer.prototype.Utils;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineGlowSlotAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.SpriteUvAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

public final class CampaignEngineGlowRenderHelper {
    public static final float HIT_GLOW_LOD_MARGIN =
            Float.parseFloat(System.getProperty("ssoptimizer.render.engineglow.lod.margin", "3000"));

    private static final int MAX_VERTICES = 8192;
    private static final float HEAD_BACKOFF = 3.0f;

    private static ByteBuffer  colorBuf;
    private static FloatBuffer vertexBuf;
    private static FloatBuffer texCoordBuf;
    private static int         numVertices;

    private CampaignEngineGlowRenderHelper() {
    }

    public record GlowScales(float sizeScale, float lengthScale) {
    }

    public static GlowScales computeScales(final float fullBrightness, final float accelBrightness) {
        float sizeScale = 1.0f + 0.75f * (1.0f - accelBrightness);
        float lengthScale = 1.0f + (accelBrightness - 0.75f);
        sizeScale *= 0.5f + 0.5f * fullBrightness;
        lengthScale *= 0.5f + 0.5f * fullBrightness;
        return new GlowScales(sizeScale, lengthScale);
    }

    public static boolean shouldSkipHitGlow(final boolean fleetInCurrentLocation,
                                            final Vector2f fleetLocation,
                                            final ViewportAPI viewport) {
        if (!fleetInCurrentLocation) {
            return false;
        }
        return !viewport.isNearViewport(fleetLocation, HIT_GLOW_LOD_MARGIN);
    }

    public static void render(final CampaignFleetMemberView view,
                              final List<?> slots,
                              final TextureObject glowTexture,
                              final Sprite hitGlow,
                              final Fader accelFader,
                              final Fader fullFader,
                              final float facing,
                              final float alphaMult,
                              final CampaignFleetAPI fleet,
                              final ViewportAPI viewport,
                              final GlowGeometryCache geometryCache) {
        final float fullBrightness = fullFader.getBrightness();
        final GlowScales scales = computeScales(fullBrightness, accelFader.getBrightness());
        hitGlow.setAlphaMult(alphaMult);

        GL11.glPushMatrix();
        GL11.glRotatef(facing, 0.0f, 0.0f, 1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        glowTexture.bind();

        renderGlowQuads(slots,
                view.getEngineColor().getCurr(),
                view.getEngineWidthMult().getCurr(),
                view.getEngineHeightMult().getCurr(),
                fullBrightness, scales.lengthScale(), alphaMult,
                geometryCache);

        final boolean skipHitGlow = fleet == null || viewport == null
                ? false
                : shouldSkipHitGlow(fleet.isInCurrentLocation(), fleet.getLocation(), viewport);
        if (!skipHitGlow) {
            renderHitGlowSprites(hitGlow, slots,
                    view.getEngineGlowColor().getCurr(),
                    scales.sizeScale(),
                    view.getEngineGlowSizeMult().getCurr(),
                    alphaMult);
        }

        GL11.glPopMatrix();
    }

    private static void renderGlowQuads(final List<?> slots,
                                        final Color color,
                                        final float engineWidthMult,
                                        final float engineHeightMult,
                                        final float fullBrightness,
                                        final float lengthScale,
                                        final float alphaMult,
                                        final GlowGeometryCache geometryCache) {
        if (slots.isEmpty()) {
            return;
        }

        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            final byte r = (byte) color.getRed();
            final byte g = (byte) color.getGreen();
            final byte b = (byte) color.getBlue();
            final byte alphaZero = (byte) (color.getAlpha() * 0.0f);
            final byte alphaFull = (byte) (color.getAlpha() * alphaMult);

            encodeSlots(slots, geometryCache, engineWidthMult, engineHeightMult, fullBrightness,
                    lengthScale, r, g, b, alphaZero, alphaFull);
            flushBatch();

            GL11.glColor4ub(r, g, b, alphaZero);
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    static void encodeSlots(final List<?> slots,
                            final GlowGeometryCache geometryCache,
                            final float engineWidthMult,
                            final float engineHeightMult,
                            final float fullBrightness,
                            final float lengthScale,
                            final byte r, final byte g, final byte b,
                            final byte alphaZero, final byte alphaFull) {
        beginBatch();
        float widthMult = engineWidthMult;
        if (widthMult > 1.0f) {
            widthMult = 1.0f + (widthMult - 1.0f) * fullBrightness;
        }
        int slotIndex = 0;
        for (final Object slot : slots) {
            encodeSlot((EngineGlowSlotAccessor) slot, slotIndex, geometryCache,
                    widthMult, lengthScale, engineHeightMult, fullBrightness,
                    r, g, b, alphaZero, alphaFull);
            slotIndex++;
        }
    }

    static void encodeSlot(final EngineGlowSlotAccessor slot,
                           final int slotIndex,
                           final GlowGeometryCache cache,
                           final float widthMult,
                           final float lengthScale,
                           final float engineHeightMult,
                           final float fullBrightness,
                           final byte r, final byte g, final byte b,
                           final byte alphaZero, final byte alphaFull) {
        if (numVertices + 8 > MAX_VERTICES) {
            flushBatch();
        }

        final int keyWidthMult = Float.floatToRawIntBits(widthMult);
        final int keyLengthScale = Float.floatToRawIntBits(lengthScale);
        final int keyHeightMult = Float.floatToRawIntBits(engineHeightMult);
        final int keyFullBrightness = Float.floatToRawIntBits(fullBrightness);
        if (cache.isHit(slotIndex, keyWidthMult, keyLengthScale, keyHeightMult, keyFullBrightness)) {
            emitCachedSlot(cache, slotIndex, r, g, b, alphaZero, alphaFull);
            return;
        }

        final float dirX;
        final float dirY;
        if (cache.hasDirection(slotIndex)) {
            dirX = cache.directionX(slotIndex);
            dirY = cache.directionY(slotIndex);
        } else {
            final float radians = (slot.ssoptimizer$getAngle() - 90.0f) * Utils.degreesToRadians;
            final float cos = (float) Math.cos(radians);
            final float sin = (float) Math.sin(radians);
            dirX = 0.0f * cos - 1.0f * sin;
            dirY = 0.0f * sin + 1.0f * cos;
            cache.storeDirection(slotIndex, dirX, dirY);
        }

        final float halfWidth = slot.ssoptimizer$getWidth() / 2.0f * widthMult;
        final float len = slot.ssoptimizer$getBaseLength() * lengthScale * engineHeightMult;
        final Vector2f offset = slot.ssoptimizer$getOffset();
        final float offX = offset.x;
        final float offY = offset.y;
        final float tailX = dirX * len + offX;
        final float tailY = dirY * len + offY;
        final float headX = dirX * -HEAD_BACKOFF + offX;
        final float headY = dirY * -HEAD_BACKOFF + offY;
        final float halfHead = halfWidth / 2.0f;

        cache.storeGeometry(slotIndex,
                keyWidthMult, keyLengthScale, keyHeightMult, keyFullBrightness,
                headX, headY + halfHead,
                headX, headY - halfHead,
                offX, offY - halfWidth,
                offX, offY + halfWidth,
                offX, offY - halfWidth,
                offX, offY + halfWidth,
                tailX, tailY + halfHead,
                tailX, tailY - halfHead);
        emitCachedSlot(cache, slotIndex, r, g, b, alphaZero, alphaFull);
    }

    private static void emitCachedSlot(final GlowGeometryCache cache, final int slotIndex,
                                       final byte r, final byte g, final byte b,
                                       final byte alphaZero, final byte alphaFull) {
        putVertex(cache.geometryAt(slotIndex, 0), cache.geometryAt(slotIndex, 1),
                1.0f, 1.0f, r, g, b, alphaZero);
        putVertex(cache.geometryAt(slotIndex, 2), cache.geometryAt(slotIndex, 3),
                1.0f, 0.0f, r, g, b, alphaZero);
        putVertex(cache.geometryAt(slotIndex, 4), cache.geometryAt(slotIndex, 5),
                0.0f, 0.0f, r, g, b, alphaFull);
        putVertex(cache.geometryAt(slotIndex, 6), cache.geometryAt(slotIndex, 7),
                0.0f, 1.0f, r, g, b, alphaFull);
        putVertex(cache.geometryAt(slotIndex, 8), cache.geometryAt(slotIndex, 9),
                0.0f, 0.0f, r, g, b, alphaFull);
        putVertex(cache.geometryAt(slotIndex, 10), cache.geometryAt(slotIndex, 11),
                0.0f, 1.0f, r, g, b, alphaFull);
        putVertex(cache.geometryAt(slotIndex, 12), cache.geometryAt(slotIndex, 13),
                1.0f, 1.0f, r, g, b, alphaZero);
        putVertex(cache.geometryAt(slotIndex, 14), cache.geometryAt(slotIndex, 15),
                1.0f, 0.0f, r, g, b, alphaZero);
    }

    static void renderHitGlowSprites(final Sprite hitGlow,
                                     final List<?> slots,
                                     final Color glowColor,
                                     final float sizeScale,
                                     final float glowSizeMult,
                                     final float alphaMult) {
        if (slots.isEmpty()) {
            return;
        }
        hitGlow.setColor(glowColor);

        final byte r = (byte) glowColor.getRed();
        final byte g = (byte) glowColor.getGreen();
        final byte b = (byte) glowColor.getBlue();
        final byte a = (byte) (int) (glowColor.getAlpha() * alphaMult);
        final TextureObject texture = hitGlow.getTexture();
        if (texture == null) {
            encodeHitGlowQuads(hitGlow, slots, sizeScale, glowSizeMult, false, r, g, b, a);
            return;
        }

        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            texture.bind();
            GL11.glBlendFunc(hitGlow.getBlendSrc(), hitGlow.getBlendDest());
            encodeHitGlowQuads(hitGlow, slots, sizeScale, glowSizeMult, true, r, g, b, a);
            flushBatch();

            GL11.glColor4ub(r, g, b, a);
        } finally {
            GL11.glPopClientAttrib();
        }
        GL11.glDisable(GL11.GL_BLEND);
    }

    static void encodeHitGlowQuads(final Sprite hitGlow,
                                   final List<?> slots,
                                   final float sizeScale,
                                   final float glowSizeMult,
                                   final boolean hasTexture,
                                   final byte r, final byte g, final byte b, final byte a) {
        beginBatch();
        final SpriteUvAccessor uv = (SpriteUvAccessor) hitGlow;
        final float u0 = uv.ssoptimizer$getTexX();
        final float v0 = uv.ssoptimizer$getTexY();
        final float u1 = u0 + uv.ssoptimizer$getTexWidth();
        final float v1 = v0 + uv.ssoptimizer$getTexHeight();
        final int offsetX = hitGlow.getOffsetX();
        final int offsetY = hitGlow.getOffsetY();
        for (final Object o : slots) {
            final EngineGlowSlotAccessor slot = (EngineGlowSlotAccessor) o;
            final float size = slot.ssoptimizer$getGlowSize() * sizeScale * glowSizeMult;
            hitGlow.setSize(size, size);
            if (!hasTexture) {
                continue;
            }
            if (numVertices + 4 > MAX_VERTICES) {
                flushBatch();
            }
            final Vector2f offset = slot.ssoptimizer$getOffset();
            final float quadX = offset.x - size / 2.0f + offsetX;
            final float quadY = offset.y - size / 2.0f + offsetY;
            putVertex(quadX, quadY, u0, v0, r, g, b, a);
            putVertex(quadX, quadY + size, u0, v1, r, g, b, a);
            putVertex(quadX + size, quadY + size, u1, v1, r, g, b, a);
            putVertex(quadX + size, quadY, u1, v0, r, g, b, a);
        }
    }

    private static void beginBatch() {
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

    private static void putVertex(final float x, final float y,
                                  final float u, final float v,
                                  final byte r, final byte g, final byte b, final byte a) {
        colorBuf.put(r).put(g).put(b).put(a);
        texCoordBuf.put(u).put(v);
        vertexBuf.put(x).put(y);
        numVertices++;
    }

    private static void flushBatch() {
        if (numVertices == 0) {
            return;
        }

        colorBuf.flip();
        vertexBuf.flip();
        texCoordBuf.flip();

        GL11.glColorPointer(4, true, 0, colorBuf);
        GL11.glVertexPointer(2, 0, vertexBuf);
        GL11.glTexCoordPointer(2, 0, texCoordBuf);

        GL11.glDrawArrays(GL11.GL_QUADS, 0, numVertices);

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
    }

    static int getNumVertices() {
        return numVertices;
    }

    static EncodedVertex vertexAt(final int index) {
        if (index < 0 || index >= numVertices) {
            throw new IndexOutOfBoundsException(
                    "vertex index " + index + " out of [0, " + numVertices + ")");
        }
        final int colorIndex = index * 4;
        final int coordIndex = index * 2;
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

    public static final class GlowGeometryCache {
        private static final int KEY_INTS_PER_SLOT = 4;
        private static final int COORDS_PER_SLOT = 16;

        private final int slotCount;
        private final int[] keys;
        private final float[] coords;
        private final float[] directions;
        private final boolean[] geometryValid;
        private final boolean[] directionValid;

        public GlowGeometryCache(final int slotCount) {
            this.slotCount = slotCount;
            this.keys = new int[slotCount * KEY_INTS_PER_SLOT];
            this.coords = new float[slotCount * COORDS_PER_SLOT];
            this.directions = new float[slotCount * 2];
            this.geometryValid = new boolean[slotCount];
            this.directionValid = new boolean[slotCount];
        }

        public int slotCount() {
            return slotCount;
        }

        boolean hasDirection(final int slotIndex) {
            return directionValid[slotIndex];
        }

        float directionX(final int slotIndex) {
            return directions[slotIndex * 2];
        }

        float directionY(final int slotIndex) {
            return directions[slotIndex * 2 + 1];
        }

        void storeDirection(final int slotIndex, final float dirX, final float dirY) {
            directions[slotIndex * 2] = dirX;
            directions[slotIndex * 2 + 1] = dirY;
            directionValid[slotIndex] = true;
        }

        boolean isHit(final int slotIndex,
                      final int widthMultBits, final int lengthScaleBits,
                      final int heightMultBits, final int fullBrightnessBits) {
            if (!geometryValid[slotIndex]) {
                return false;
            }
            final int base = slotIndex * KEY_INTS_PER_SLOT;
            return keys[base] == widthMultBits
                    && keys[base + 1] == lengthScaleBits
                    && keys[base + 2] == heightMultBits
                    && keys[base + 3] == fullBrightnessBits;
        }

        void storeGeometry(final int slotIndex,
                           final int widthMultBits, final int lengthScaleBits,
                           final int heightMultBits, final int fullBrightnessBits,
                           final float x0, final float y0, final float x1, final float y1,
                           final float x2, final float y2, final float x3, final float y3,
                           final float x4, final float y4, final float x5, final float y5,
                           final float x6, final float y6, final float x7, final float y7) {
            final int keyBase = slotIndex * KEY_INTS_PER_SLOT;
            keys[keyBase] = widthMultBits;
            keys[keyBase + 1] = lengthScaleBits;
            keys[keyBase + 2] = heightMultBits;
            keys[keyBase + 3] = fullBrightnessBits;
            final int coordBase = slotIndex * COORDS_PER_SLOT;
            coords[coordBase] = x0;
            coords[coordBase + 1] = y0;
            coords[coordBase + 2] = x1;
            coords[coordBase + 3] = y1;
            coords[coordBase + 4] = x2;
            coords[coordBase + 5] = y2;
            coords[coordBase + 6] = x3;
            coords[coordBase + 7] = y3;
            coords[coordBase + 8] = x4;
            coords[coordBase + 9] = y4;
            coords[coordBase + 10] = x5;
            coords[coordBase + 11] = y5;
            coords[coordBase + 12] = x6;
            coords[coordBase + 13] = y6;
            coords[coordBase + 14] = x7;
            coords[coordBase + 15] = y7;
            geometryValid[slotIndex] = true;
        }

        float geometryAt(final int slotIndex, final int i) {
            return coords[slotIndex * COORDS_PER_SLOT + i];
        }
    }
}
