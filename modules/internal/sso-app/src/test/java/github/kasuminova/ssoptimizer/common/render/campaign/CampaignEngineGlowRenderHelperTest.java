package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.graphics.Sprite;
import com.fs.starfarer.prototype.Utils;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineGlowSlotAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.SpriteUvAccessor;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CampaignEngineGlowRenderHelperTest {
    private final List<RecordedVertex> actual = new ArrayList<>();

    @Test
    void computeScalesMatchesVanillaFormula() {
        final float[] brightness = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f, 0.33333334f};
        for (final float var4 : brightness) {
            for (final float accel : brightness) {
                float var5 = 1.0f + 0.75f * (1.0f - accel);
                float var6 = 1.0f + (accel - 0.75f);
                var5 *= 0.5f + 0.5f * var4;
                var6 *= 0.5f + 0.5f * var4;

                CampaignEngineGlowRenderHelper.GlowScales scales =
                        CampaignEngineGlowRenderHelper.computeScales(var4, accel);
                assertEquals(Float.floatToRawIntBits(var5), Float.floatToRawIntBits(scales.sizeScale()),
                        "sizeScale full=" + var4 + " accel=" + accel);
                assertEquals(Float.floatToRawIntBits(var6), Float.floatToRawIntBits(scales.lengthScale()),
                        "lengthScale full=" + var4 + " accel=" + accel);
            }
        }
    }

    @Test
    void singleSlotEmitsTwoQuadsInVanillaOrder() {
        FakeSlot slot = new FakeSlot(180.0f, 10.0f, 6.0f, 4.0f, new Vector2f(50.0f, 20.0f));
        Color color = new Color(100, 150, 200, 255);

        encodeOptimized(List.of(slot), color, 1.0f, 1.0f, 3.0f, 1.0f, 2.0f);

        assertEquals(8, actual.size());
        List<RecordedVertex> vs = actual;

        assertVertex(vs.get(0), 53.0f, 21.0f, 1.0f, 1.0f, 0);
        assertVertex(vs.get(1), 53.0f, 19.0f, 1.0f, 0.0f, 0);
        assertVertex(vs.get(2), 50.0f, 18.0f, 0.0f, 0.0f, 255);
        assertVertex(vs.get(3), 50.0f, 22.0f, 0.0f, 1.0f, 255);
        assertVertex(vs.get(4), 50.0f, 18.0f, 0.0f, 0.0f, 255);
        assertVertex(vs.get(5), 50.0f, 22.0f, 0.0f, 1.0f, 255);
        assertVertex(vs.get(6), -10.0f, 21.0f, 1.0f, 1.0f, 0);
        assertVertex(vs.get(7), -10.0f, 19.0f, 1.0f, 0.0f, 0);
        for (final RecordedVertex v : vs) {
            assertEquals(100, v.r());
            assertEquals(150, v.g());
            assertEquals(200, v.b());
        }
    }

    @Test
    void widthMultAboveOneIsDampedByFullBrightness() {
        FakeSlot slot = new FakeSlot(180.0f, 10.0f, 6.0f, 4.0f, new Vector2f(0.0f, 0.0f));
        Color color = new Color(255, 255, 255, 255);

        encodeOptimized(List.of(slot), color, 1.0f, 0.0f, 1.0f, 2.0f, 1.0f);
        assertEquals(-2.0f, actual.get(2).y(), 0.0f);

        encodeOptimized(List.of(slot), color, 1.0f, 1.0f, 1.0f, 2.0f, 1.0f);
        assertEquals(-4.0f, actual.get(2).y(), 0.0f);
    }

    @Test
    void encodedOutputMatchesReferenceBitwiseAcrossRandomStates() {
        Random random = new Random(0xE661_0FFL);
        for (int trial = 0; trial < 300; trial++) {
            int slotCount = random.nextInt(9);
            List<FakeSlot> slots = new ArrayList<>();
            for (int i = 0; i < slotCount; i++) {
                slots.add(randomSlot(random));
            }
            CampaignEngineGlowRenderHelper.GlowGeometryCache cache =
                    new CampaignEngineGlowRenderHelper.GlowGeometryCache(slotCount);

            float fullBrightness = random.nextFloat();
            float lengthScale = random.nextFloat() * 2f;
            float widthMultCurr = random.nextBoolean()
                    ? random.nextFloat()
                    : 1.0f + random.nextFloat() * 2f;
            float heightMultCurr = random.nextFloat() * 2f;

            int frames = 1 + random.nextInt(4);
            for (int frame = 0; frame < frames; frame++) {
                if (frame > 0 && random.nextBoolean()) {
                    fullBrightness = random.nextFloat();
                    lengthScale = random.nextFloat() * 2f;
                    widthMultCurr = random.nextBoolean()
                            ? random.nextFloat()
                            : 1.0f + random.nextFloat() * 2f;
                    heightMultCurr = random.nextFloat() * 2f;
                }
                Color color = new Color(random.nextInt(256), random.nextInt(256),
                        random.nextInt(256), random.nextInt(256));
                float alphaMult = random.nextFloat() * 1.5f;

                List<RecordedVertex> expected = new ArrayList<>();
                referenceEncode(slots, color, alphaMult, fullBrightness, lengthScale,
                        widthMultCurr, heightMultCurr, expected);

                encodeOptimized(slots, cache, color, alphaMult, fullBrightness, lengthScale,
                        widthMultCurr, heightMultCurr);

                String prefix = "trial " + trial + " frame " + frame + " ";
                assertEquals(expected.size(), actual.size(),
                        prefix + "顶点数");
                for (int i = 0; i < expected.size(); i++) {
                    RecordedVertex e = expected.get(i);
                    RecordedVertex a = actual.get(i);
                    assertEquals(Float.floatToRawIntBits(e.x), Float.floatToRawIntBits(a.x()), prefix + "v" + i + " x");
                    assertEquals(Float.floatToRawIntBits(e.y), Float.floatToRawIntBits(a.y()), prefix + "v" + i + " y");
                    assertEquals(Float.floatToRawIntBits(e.u), Float.floatToRawIntBits(a.u()), prefix + "v" + i + " u");
                    assertEquals(Float.floatToRawIntBits(e.v), Float.floatToRawIntBits(a.v()), prefix + "v" + i + " v");
                    assertEquals(e.r, a.r(), prefix + "v" + i + " r");
                    assertEquals(e.g, a.g(), prefix + "v" + i + " g");
                    assertEquals(e.b, a.b(), prefix + "v" + i + " b");
                    assertEquals(e.a, a.a(), prefix + "v" + i + " a");
                }
            }
        }
    }

    @Test
    void cachedSlotGeometryEmitsIdenticalBitsOnHit() {
        List<FakeSlot> slots = List.of(
                new FakeSlot(180.0f, 10.0f, 6.0f, 4.0f, new Vector2f(50.0f, 20.0f)),
                new FakeSlot(173.5f, 14.0f, 5.0f, 3.0f, new Vector2f(-30.0f, 12.0f)));
        CampaignEngineGlowRenderHelper.GlowGeometryCache cache =
                new CampaignEngineGlowRenderHelper.GlowGeometryCache(slots.size());
        Color color = new Color(100, 150, 200, 255);

        encodeOptimized(slots, cache, color, 1.0f, 1.0f, 3.0f, 1.0f, 2.0f);
        List<RecordedVertex> firstPass = List.copyOf(actual);

        encodeOptimized(slots, cache, color, 1.0f, 1.0f, 3.0f, 1.0f, 2.0f);
        assertEquals(firstPass.size(), actual.size());
        for (int i = 0; i < firstPass.size(); i++) {
            RecordedVertex e = firstPass.get(i);
            RecordedVertex a = actual.get(i);
            assertEquals(Float.floatToRawIntBits(e.x()), Float.floatToRawIntBits(a.x()), "v" + i + " x");
            assertEquals(Float.floatToRawIntBits(e.y()), Float.floatToRawIntBits(a.y()), "v" + i + " y");
            assertEquals(e.r(), a.r(), "v" + i + " r");
            assertEquals(e.a(), a.a(), "v" + i + " a");
        }
    }

    @Test
    void cacheHitPathWritesCurrentFrameColors() {
        List<FakeSlot> slots = List.of(
                new FakeSlot(180.0f, 10.0f, 6.0f, 4.0f, new Vector2f(50.0f, 20.0f)));
        CampaignEngineGlowRenderHelper.GlowGeometryCache cache =
                new CampaignEngineGlowRenderHelper.GlowGeometryCache(slots.size());

        encodeOptimized(slots, cache, new Color(1, 2, 3, 255), 1.0f, 1.0f, 3.0f, 1.0f, 2.0f);

        Color colorB = new Color(200, 100, 50, 128);
        float alphaMult = 0.5f;
        List<RecordedVertex> expected = new ArrayList<>();
        referenceEncode(slots, colorB, alphaMult, 1.0f, 3.0f, 1.0f, 2.0f, expected);

        encodeOptimized(slots, cache, colorB, alphaMult, 1.0f, 3.0f, 1.0f, 2.0f);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            RecordedVertex e = expected.get(i);
            RecordedVertex a = actual.get(i);
            assertEquals(Float.floatToRawIntBits(e.x), Float.floatToRawIntBits(a.x()), "v" + i + " x");
            assertEquals(Float.floatToRawIntBits(e.y), Float.floatToRawIntBits(a.y()), "v" + i + " y");
            assertEquals(e.r, a.r(), "v" + i + " r");
            assertEquals(e.g, a.g(), "v" + i + " g");
            assertEquals(e.b, a.b(), "v" + i + " b");
            assertEquals(e.a, a.a(), "v" + i + " a");
        }
    }

    @Test
    void transitionFramesMissCacheAndMatchReference() {
        List<FakeSlot> slots = List.of(
                new FakeSlot(180.0f, 10.0f, 6.0f, 4.0f, new Vector2f(50.0f, 20.0f)),
                new FakeSlot(90.0f, 8.0f, 5.0f, 1.0f, new Vector2f(-10.0f, -6.0f)));
        CampaignEngineGlowRenderHelper.GlowGeometryCache cache =
                new CampaignEngineGlowRenderHelper.GlowGeometryCache(slots.size());
        Color color = new Color(255, 255, 255, 255);

        float brightness = 0.0f;
        for (int frame = 0; frame <= 10; frame++, brightness += 0.1f) {
            List<RecordedVertex> expected = new ArrayList<>();
            referenceEncode(slots, color, 1.0f, brightness, 3.0f, 1.6f, 2.0f, expected);
            encodeOptimized(slots, cache, color, 1.0f, brightness, 3.0f, 1.6f, 2.0f);

            assertEquals(expected.size(), actual.size(),
                    "frame " + frame + " 顶点数");
            for (int i = 0; i < expected.size(); i++) {
                RecordedVertex e = expected.get(i);
                RecordedVertex a = actual.get(i);
                assertEquals(Float.floatToRawIntBits(e.x), Float.floatToRawIntBits(a.x()),
                        "frame " + frame + " v" + i + " x");
                assertEquals(Float.floatToRawIntBits(e.y), Float.floatToRawIntBits(a.y()),
                        "frame " + frame + " v" + i + " y");
            }
        }
    }

    @Test
    void emptySlotsEncodeNothing() {
        encodeOptimized(List.of(), new Color(1, 2, 3, 255), 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        assertEquals(0, actual.size());
    }

    @Test
    void hitGlowBatchedQuadsMatchVanillaReferenceWithAtlasUv() {
        Random random = new Random(0xA71A5L);
        for (int trial = 0; trial < 200; trial++) {
            int slotCount = 1 + random.nextInt(6);
            List<FakeSlot> slots = new ArrayList<>();
            for (int i = 0; i < slotCount; i++) {
                slots.add(randomSlot(random));
            }
            boolean atlas = random.nextBoolean();
            float texX = atlas ? random.nextFloat() * 0.9f : 0.0f;
            float texY = atlas ? random.nextFloat() * 0.9f : 0.0f;
            float texWidth = atlas ? 0.01f + random.nextFloat() * 0.09f : 1.0f;
            float texHeight = atlas ? 0.01f + random.nextFloat() * 0.09f : 1.0f;
            TestSprite hitGlow = new TestSprite(texX, texY, texWidth, texHeight);
            Color glowColor = new Color(random.nextInt(256), random.nextInt(256),
                    random.nextInt(256), random.nextInt(256));
            float sizeScale = 0.5f + random.nextFloat();
            float glowSizeMult = 0.5f + random.nextFloat();
            float alphaMult = random.nextFloat() * 1.5f;

            byte r = (byte) glowColor.getRed();
            byte g = (byte) glowColor.getGreen();
            byte b = (byte) glowColor.getBlue();
            byte a = (byte) (int) (glowColor.getAlpha() * alphaMult);
            actual.clear();
            CampaignEngineGlowRenderHelper.encodeHitGlowQuads(
                    hitGlow, slots, sizeScale, glowSizeMult, true, r, g, b, a, this::sample);

            List<RecordedVertex> expected = new ArrayList<>();
            referenceHitGlow(slots, sizeScale, glowSizeMult, alphaMult,
                    texX, texY, texWidth, texHeight, glowColor, expected);

            String prefix = "trial " + trial + " ";
            assertEquals(expected.size(), actual.size(),
                    prefix + "顶点数");
            for (int i = 0; i < expected.size(); i++) {
                RecordedVertex e = expected.get(i);
                RecordedVertex v = actual.get(i);
                assertEquals(Float.floatToRawIntBits(e.x), Float.floatToRawIntBits(v.x()), prefix + "v" + i + " x");
                assertEquals(Float.floatToRawIntBits(e.y), Float.floatToRawIntBits(v.y()), prefix + "v" + i + " y");
                assertEquals(Float.floatToRawIntBits(e.u), Float.floatToRawIntBits(v.u()), prefix + "v" + i + " u");
                assertEquals(Float.floatToRawIntBits(e.v), Float.floatToRawIntBits(v.v()), prefix + "v" + i + " v");
                assertEquals(e.r, v.r(), prefix + "v" + i + " r");
                assertEquals(e.g, v.g(), prefix + "v" + i + " g");
                assertEquals(e.b, v.b(), prefix + "v" + i + " b");
                assertEquals(e.a, v.a(), prefix + "v" + i + " a");
            }
        }
    }

    @Test
    void queuedChunksKeepSlotSnapshotsWhenSubsequentFramesReplaceCacheEntries() {
        var slots = new ArrayList<FakeSlot>();
        var random = new Random(40071);
        for (int i = 0; i < 2301; i++) slots.add(randomSlot(random));
        var meshes = new ArrayList<CampaignArrayMesh>();
        var cache = new CampaignEngineGlowRenderHelper.GlowGeometryCache(slots.size());
        CampaignEngineGlowRenderHelper.encodeSlots(slots, cache, 2f, 1f, 0.5f, 1f,
                (byte) 1, (byte) 2, (byte) 3, (byte) 0, (byte) 96, meshes::add);
        var sprite = new TestSprite(0.25f, 0.125f, 0.5f, 0.75f);
        CampaignEngineGlowRenderHelper.encodeHitGlowQuads(sprite, slots, 1.5f, 1f, true,
                (byte) 1, (byte) 2, (byte) 3, (byte) 96, meshes::add);
        var expected = new ArrayList<RecordedVertex>();
        Color color = new Color(1, 2, 3, 96);
        referenceEncode(slots, color, 1, 0.5f, 1, 2, 1, expected);
        referenceHitGlow(slots, 1.5f, 1f, 1f, 0.25f, 0.125f, 0.5f, 0.75f, color, expected);
        for (var slot : slots) slot.offset.set(2000, 3000);
        var nextFrame = new ArrayList<CampaignArrayMesh>();
        CampaignEngineGlowRenderHelper.encodeSlots(slots, cache, 2f, 1f, 0.75f, 1f,
                (byte) 99, (byte) 2, (byte) 3, (byte) 0, (byte) 96, nextFrame::add);
        assertEquals(List.of(8192, 8192, 2024, 8192, 1012), meshes.stream().map(CampaignArrayMesh::vertexCount).toList());
        meshes.forEach(this::sample);
        assertEquals(expected, actual);
    }

    private static void referenceEncode(final List<FakeSlot> slots,
                                        final Color var7,
                                        final float var3,
                                        final float var4,
                                        final float var6,
                                        final float widthMultCurr,
                                        final float heightMultCurr,
                                        final List<RecordedVertex> out) {
        for (final FakeSlot var8 : slots) {
            float var10 = widthMultCurr;
            if (var10 > 1.0f) {
                var10 = 1.0f + (var10 - 1.0f) * var4;
            }
            float var11 = var8.width / 2.0f * var10;
            float var12 = var8.baseLength * var6 * heightMultCurr;
            float var13 = 3.0f;
            Vector2f var14 = var8.offset;
            Vector2f var15 = Utils.rotate(new Vector2f(0.0f, 1.0f), var8.angle - 90.0f);
            Vector2f var16 = new Vector2f(var15);
            var15.scale(var12);
            var15.x = var15.x + var14.x;
            var15.y = var15.y + var14.y;
            var16.scale(-var13);
            var16.x = var16.x + var14.x;
            var16.y = var16.y + var14.y;

            int r = var7.getRed();
            int g = var7.getGreen();
            int b = var7.getBlue();
            int a0 = ((byte) (var7.getAlpha() * 0.0f)) & 0xFF;
            out.add(new RecordedVertex(var16.x, var16.y + var11 / 2.0f, 1.0f, 1.0f, r, g, b, a0));
            out.add(new RecordedVertex(var16.x, var16.y - var11 / 2.0f, 1.0f, 0.0f, r, g, b, a0));
            int aF = ((byte) (var7.getAlpha() * var3)) & 0xFF;
            out.add(new RecordedVertex(var14.x, var14.y - var11, 0.0f, 0.0f, r, g, b, aF));
            out.add(new RecordedVertex(var14.x, var14.y + var11, 0.0f, 1.0f, r, g, b, aF));
            out.add(new RecordedVertex(var14.x, var14.y - var11, 0.0f, 0.0f, r, g, b, aF));
            out.add(new RecordedVertex(var14.x, var14.y + var11, 0.0f, 1.0f, r, g, b, aF));
            out.add(new RecordedVertex(var15.x, var15.y + var11 / 2.0f, 1.0f, 1.0f, r, g, b, a0));
            out.add(new RecordedVertex(var15.x, var15.y - var11 / 2.0f, 1.0f, 0.0f, r, g, b, a0));
        }
    }

    private static void referenceHitGlow(final List<FakeSlot> slots,
                                         final float sizeScale,
                                         final float glowSizeMult,
                                         final float alphaMult,
                                         final float texX, final float texY,
                                         final float texWidth, final float texHeight,
                                         final Color glowColor,
                                         final List<RecordedVertex> out) {
        int r = glowColor.getRed();
        int g = glowColor.getGreen();
        int b = glowColor.getBlue();
        int a = ((byte) (int) (glowColor.getAlpha() * alphaMult)) & 0xFF;
        for (final FakeSlot slot : slots) {
            float size = slot.glowSize * sizeScale * glowSizeMult;
            float quadX = slot.offset.x - size / 2.0f + 0;
            float quadY = slot.offset.y - size / 2.0f + 0;
            out.add(new RecordedVertex(quadX, quadY, texX, texY, r, g, b, a));
            out.add(new RecordedVertex(quadX, quadY + size, texX, texY + texHeight, r, g, b, a));
            out.add(new RecordedVertex(quadX + size, quadY + size,
                    texX + texWidth, texY + texHeight, r, g, b, a));
            out.add(new RecordedVertex(quadX + size, quadY, texX + texWidth, texY, r, g, b, a));
        }
    }

    private record RecordedVertex(float x, float y, float u, float v, int r, int g, int b, int a) {
    }

    private static final class FakeSlot implements EngineGlowSlotAccessor {
        private final float    angle;
        private final float    baseLength;
        private final float    glowSize;
        private final float    width;
        private final Vector2f offset;

        private FakeSlot(final float angle, final float baseLength, final float glowSize,
                         final float width, final Vector2f offset) {
            this.angle = angle;
            this.baseLength = baseLength;
            this.glowSize = glowSize;
            this.width = width;
            this.offset = offset;
        }

        @Override
        public float ssoptimizer$getAngle() {
            return angle;
        }

        @Override
        public float ssoptimizer$getBaseLength() {
            return baseLength;
        }

        @Override
        public float ssoptimizer$getGlowSize() {
            return glowSize;
        }

        @Override
        public float ssoptimizer$getWidth() {
            return width;
        }

        @Override
        public Vector2f ssoptimizer$getOffset() {
            return offset;
        }
    }

    private static final class TestSprite extends Sprite implements SpriteUvAccessor {
        private final float texX;
        private final float texY;
        private final float texWidth;
        private final float texHeight;
        private TestSprite(final float texX, final float texY,
                               final float texWidth, final float texHeight) {
            this.texX = texX;
            this.texY = texY;
            this.texWidth = texWidth;
            this.texHeight = texHeight;
        }

        @Override
        public float ssoptimizer$getTexX() {
            return texX;
        }

        @Override
        public float ssoptimizer$getTexY() {
            return texY;
        }

        @Override
        public float ssoptimizer$getTexWidth() {
            return texWidth;
        }

        @Override
        public float ssoptimizer$getTexHeight() {
            return texHeight;
        }
    }

    private void encodeOptimized(final List<FakeSlot> slots,
                                        final Color color,
                                        final float alphaMult,
                                        final float fullBrightness,
                                        final float lengthScale,
                                        final float widthMultCurr,
                                        final float heightMultCurr) {
        encodeOptimized(slots,
                new CampaignEngineGlowRenderHelper.GlowGeometryCache(slots.size()),
                color, alphaMult, fullBrightness, lengthScale, widthMultCurr, heightMultCurr);
    }

    private void encodeOptimized(final List<FakeSlot> slots,
                                        final CampaignEngineGlowRenderHelper.GlowGeometryCache cache,
                                        final Color color,
                                        final float alphaMult,
                                        final float fullBrightness,
                                        final float lengthScale,
                                        final float widthMultCurr,
                                        final float heightMultCurr) {
        actual.clear();
        CampaignEngineGlowRenderHelper.encodeSlots(
                slots, cache, widthMultCurr, heightMultCurr, fullBrightness, lengthScale,
                (byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(),
                (byte) (color.getAlpha() * 0.0f), (byte) (color.getAlpha() * alphaMult), this::sample);
    }

    private void sample(CampaignArrayMesh mesh) {
        mesh.expand((x, y, u, v, rgba) -> actual.add(new RecordedVertex(x, y, u, v,
                rgba >>> 24, rgba >>> 16 & 255, rgba >>> 8 & 255, rgba & 255)));
    }

    private static void assertVertex(final RecordedVertex v,
                                     final float x, final float y,
                                     final float u, final float texV, final int a) {
        assertEquals(x, v.x(), 1e-4f, "x");
        assertEquals(y, v.y(), 1e-4f, "y");
        assertEquals(u, v.u(), 0.0f, "u");
        assertEquals(texV, v.v(), 0.0f, "v");
        assertEquals(a, v.a(), "a");
    }

    private static FakeSlot randomSlot(final Random random) {
        float angle = switch (random.nextInt(4)) {
            case 0 -> 90.0f;
            case 1 -> 180.0f;
            default -> random.nextFloat() * 720f - 360f;
        };
        float width = random.nextInt(8) == 0 ? 1.0f : 1.0f + random.nextFloat() * 40f;
        return new FakeSlot(
                angle,
                random.nextFloat() * 60f,
                random.nextFloat() * 30f,
                width,
                new Vector2f(random.nextFloat() * 200f - 100f, random.nextFloat() * 200f - 100f));
    }
}
