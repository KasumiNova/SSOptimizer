package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.CampaignFleetMemberView;
import com.fs.starfarer.prototype.Utils;
import github.kasuminova.ssoptimizer.bridge.opengl.CampaignArrayDraw;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineGlowSlotAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.SpriteUvAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;

public final class CampaignEngineGlowRenderHelper {
    public static final float HIT_GLOW_LOD_MARGIN =
            Float.parseFloat(System.getProperty("ssoptimizer.render.engineglow.lod.margin", "3000"));
    private static final int MAX_VERTICES = 8192;
    private static final float HEAD_BACKOFF = 3.0f;
    private static final ThreadLocal<CampaignMeshBuffers> DIRECT = ThreadLocal.withInitial(CampaignMeshBuffers::new);

    private CampaignEngineGlowRenderHelper() {}

    public record GlowScales(float sizeScale, float lengthScale) {}

    public static GlowScales computeScales(float fullBrightness, float accelBrightness) {
        float sizeScale = 1.0f + 0.75f * (1.0f - accelBrightness);
        float lengthScale = 1.0f + (accelBrightness - 0.75f);
        sizeScale *= 0.5f + 0.5f * fullBrightness;
        lengthScale *= 0.5f + 0.5f * fullBrightness;
        return new GlowScales(sizeScale, lengthScale);
    }

    public static boolean shouldSkipHitGlow(boolean fleetInCurrentLocation, Vector2f fleetLocation, ViewportAPI viewport) {
        return fleetInCurrentLocation && !viewport.isNearViewport(fleetLocation, HIT_GLOW_LOD_MARGIN);
    }

    public static void render(CampaignFleetMemberView view, List<?> slots, TextureObject glowTexture, Sprite hitGlow,
                              Fader accelFader, Fader fullFader, float facing, float alphaMult, CampaignFleetAPI fleet,
                              ViewportAPI viewport, GlowGeometryCache geometryCache) {
        float fullBrightness = fullFader.getBrightness();
        GlowScales scales = computeScales(fullBrightness, accelFader.getBrightness());
        hitGlow.setAlphaMult(alphaMult);
        GL11.glPushMatrix();
        try {
            GL11.glRotatef(facing, 0.0f, 0.0f, 1.0f);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            glowTexture.bind();
            renderGlowQuads(slots, view.getEngineColor().getCurr(), view.getEngineWidthMult().getCurr(),
                    view.getEngineHeightMult().getCurr(), fullBrightness, scales.lengthScale(), alphaMult, geometryCache);
            if (fleet == null || viewport == null || !shouldSkipHitGlow(fleet.isInCurrentLocation(), fleet.getLocation(), viewport)) {
                renderHitGlowSprites(hitGlow, slots, view.getEngineGlowColor().getCurr(), scales.sizeScale(),
                        view.getEngineGlowSizeMult().getCurr(), alphaMult);
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static void enableArrays() {
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
    }

    private static void draw(CampaignArrayMesh mesh) {
        if (GlDispatch.canSubmitOwnedDraw()) CampaignArrayDraw.submit(mesh);
        else DIRECT.get().drawRecorded(mesh);
    }

    private static void renderGlowQuads(List<?> slots, Color color, float widthMult, float heightMult,
                                        float fullBrightness, float lengthScale, float alphaMult, GlowGeometryCache cache) {
        if (slots.isEmpty()) return;
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            enableArrays();
            byte r = (byte) color.getRed(), g = (byte) color.getGreen(), b = (byte) color.getBlue();
            byte zero = (byte) (color.getAlpha() * 0.0f), full = (byte) (color.getAlpha() * alphaMult);
            encodeSlots(slots, cache, widthMult, heightMult, fullBrightness, lengthScale, r, g, b, zero, full,
                    CampaignEngineGlowRenderHelper::draw);
            GL11.glColor4ub(r, g, b, zero);
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    static void encodeSlots(List<?> slots, GlowGeometryCache cache, float widthMult, float heightMult,
                            float fullBrightness, float lengthScale, byte r, byte g, byte b,
                            byte alphaZero, byte alphaFull, Consumer<CampaignArrayMesh> output) {
        if (slots.isEmpty()) return;
        if (widthMult > 1.0f) widthMult = 1.0f + (widthMult - 1.0f) * fullBrightness;
        int rgb = (r & 255) << 24 | (g & 255) << 16 | (b & 255) << 8;
        int zero = rgb | alphaZero & 255, full = rgb | alphaFull & 255;
        int chunk = MAX_VERTICES / 8, index = 0, count = 0;
        GlowSlot[] group = new GlowSlot[Math.min(chunk, slots.size())];
        for (Object slot : slots) {
            if (count == group.length) {
                output.accept(new GlowQuads(group, count, zero, full));
                group = new GlowSlot[Math.min(chunk, slots.size() - index)];
                count = 0;
            }
            group[count++] = cache.capture(index++, (EngineGlowSlotAccessor) slot,
                    widthMult, lengthScale, heightMult, fullBrightness);
        }
        output.accept(new GlowQuads(group, count, zero, full));
    }

    private record GlowSlot(float x, float y, float dirX, float dirY, float halfWidth, float length) {
        void expand(CampaignArrayMesh.Vertex out, int zero, int full) {
            float tailX = dirX * length + x, tailY = dirY * length + y;
            float headX = dirX * -HEAD_BACKOFF + x, headY = dirY * -HEAD_BACKOFF + y;
            float halfHead = halfWidth / 2.0f;
            out.put(headX, headY + halfHead, 1, 1, zero);
            out.put(headX, headY - halfHead, 1, 0, zero);
            out.put(x, y - halfWidth, 0, 0, full);
            out.put(x, y + halfWidth, 0, 1, full);
            out.put(x, y - halfWidth, 0, 0, full);
            out.put(x, y + halfWidth, 0, 1, full);
            out.put(tailX, tailY + halfHead, 1, 1, zero);
            out.put(tailX, tailY - halfHead, 1, 0, zero);
        }
    }

    private record GlowQuads(GlowSlot[] slots, int count, int zero, int full) implements CampaignArrayMesh {
        public int mode() { return GL11.GL_QUADS; }
        public int vertexCount() { return count * 8; }
        public void expand(Vertex out) {
            for (int i = 0; i < count; i++) slots[i].expand(out, zero, full);
        }
    }

    static void renderHitGlowSprites(Sprite hitGlow, List<?> slots, Color color, float sizeScale,
                                     float glowSizeMult, float alphaMult) {
        if (slots.isEmpty()) return;
        hitGlow.setColor(color);
        byte r = (byte) color.getRed(), g = (byte) color.getGreen(), b = (byte) color.getBlue();
        byte a = (byte) (int) (color.getAlpha() * alphaMult);
        TextureObject texture = hitGlow.getTexture();
        if (texture == null) {
            encodeHitGlowQuads(hitGlow, slots, sizeScale, glowSizeMult, false, r, g, b, a, CampaignEngineGlowRenderHelper::draw);
            return;
        }
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            enableArrays();
            texture.bind();
            GL11.glBlendFunc(hitGlow.getBlendSrc(), hitGlow.getBlendDest());
            encodeHitGlowQuads(hitGlow, slots, sizeScale, glowSizeMult, true, r, g, b, a, CampaignEngineGlowRenderHelper::draw);
            GL11.glColor4ub(r, g, b, a);
        } finally {
            GL11.glPopClientAttrib();
        }
        GL11.glDisable(GL11.GL_BLEND);
    }

    static void encodeHitGlowQuads(Sprite hitGlow, List<?> slots, float sizeScale, float glowSizeMult,
                                   boolean hasTexture, byte r, byte g, byte b, byte a, Consumer<CampaignArrayMesh> output) {
        SpriteUvAccessor uv = (SpriteUvAccessor) hitGlow;
        float u0 = uv.ssoptimizer$getTexX(), v0 = uv.ssoptimizer$getTexY();
        float u1 = u0 + uv.ssoptimizer$getTexWidth(), v1 = v0 + uv.ssoptimizer$getTexHeight();
        int offsetX = hitGlow.getOffsetX(), offsetY = hitGlow.getOffsetY();
        int chunk = MAX_VERTICES / 4, index = 0, used = 0;
        int rgba = (r & 255) << 24 | (g & 255) << 16 | (b & 255) << 8 | a & 255;
        float[] points = hasTexture ? new float[Math.min(chunk, slots.size()) * 3] : null;
        for (Object o : slots) {
            EngineGlowSlotAccessor slot = (EngineGlowSlotAccessor) o;
            float size = slot.ssoptimizer$getGlowSize() * sizeScale * glowSizeMult;
            hitGlow.setSize(size, size);
            if (!hasTexture) continue;
            if (used == points.length) {
                output.accept(new HitQuads(points, used, offsetX, offsetY, u0, v0, u1, v1, rgba));
                points = new float[Math.min(chunk, slots.size() - index) * 3];
                used = 0;
            }
            Vector2f offset = slot.ssoptimizer$getOffset();
            points[used++] = offset.x;
            points[used++] = offset.y;
            points[used++] = size;
            index++;
        }
        if (used > 0) output.accept(new HitQuads(points, used, offsetX, offsetY, u0, v0, u1, v1, rgba));
    }

    private record HitQuads(float[] points, int used, int offsetX, int offsetY,
                            float u0, float v0, float u1, float v1, int rgba) implements CampaignArrayMesh {
        public int mode() { return GL11.GL_QUADS; }
        public int vertexCount() { return used / 3 * 4; }
        public void expand(Vertex out) {
            for (int i = 0; i < used; i += 3) {
                float size = points[i + 2];
                float x = points[i] - size / 2.0f + offsetX;
                float y = points[i + 1] - size / 2.0f + offsetY;
                out.put(x, y, u0, v0, rgba);
                out.put(x, y + size, u0, v1, rgba);
                out.put(x + size, y + size, u1, v1, rgba);
                out.put(x + size, y, u1, v0, rgba);
            }
        }
    }

    public static final class GlowGeometryCache {
        private final int[] keys;
        private final GlowSlot[] slots;

        public GlowGeometryCache(int slotCount) {
            keys = new int[slotCount * 4];
            slots = new GlowSlot[slotCount];
        }

        public int slotCount() { return slots.length; }

        private GlowSlot capture(int index, EngineGlowSlotAccessor slot, float widthMult,
                                 float lengthScale, float heightMult, float fullBrightness) {
            int width = Float.floatToRawIntBits(widthMult), length = Float.floatToRawIntBits(lengthScale);
            int height = Float.floatToRawIntBits(heightMult), brightness = Float.floatToRawIntBits(fullBrightness);
            int base = index * 4;
            GlowSlot cached = slots[index];
            if (cached != null && keys[base] == width && keys[base + 1] == length
                    && keys[base + 2] == height && keys[base + 3] == brightness) return cached;
            float dirX, dirY;
            if (cached == null) {
                float radians = (slot.ssoptimizer$getAngle() - 90.0f) * Utils.degreesToRadians;
                float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
                dirX = 0.0f * cos - 1.0f * sin;
                dirY = 0.0f * sin + 1.0f * cos;
            } else {
                dirX = cached.dirX();
                dirY = cached.dirY();
            }
            float halfWidth = slot.ssoptimizer$getWidth() / 2.0f * widthMult;
            float len = slot.ssoptimizer$getBaseLength() * lengthScale * heightMult;
            Vector2f offset = slot.ssoptimizer$getOffset();
            GlowSlot result = new GlowSlot(offset.x, offset.y, dirX, dirY, halfWidth, len);
            keys[base] = width;
            keys[base + 1] = length;
            keys[base + 2] = height;
            keys[base + 3] = brightness;
            slots[index] = result;
            return result;
        }
    }
}
