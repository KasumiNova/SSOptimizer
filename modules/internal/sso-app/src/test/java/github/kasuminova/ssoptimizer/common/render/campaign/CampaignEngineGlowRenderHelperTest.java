package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.graphics.Sprite;
import com.fs.starfarer.api.combat.ViewportAPI;
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

        assertEquals(8, CampaignEngineGlowRenderHelper.getNumVertices());
        List<CampaignEngineGlowRenderHelper.EncodedVertex> vs = vertices(8);

        assertVertex(vs.get(0), 53.0f, 21.0f, 1.0f, 1.0f, 0);
        assertVertex(vs.get(1), 53.0f, 19.0f, 1.0f, 0.0f, 0);
        assertVertex(vs.get(2), 50.0f, 18.0f, 0.0f, 0.0f, 255);
        assertVertex(vs.get(3), 50.0f, 22.0f, 0.0f, 1.0f, 255);
        assertVertex(vs.get(4), 50.0f, 18.0f, 0.0f, 0.0f, 255);
        assertVertex(vs.get(5), 50.0f, 22.0f, 0.0f, 1.0f, 255);
        assertVertex(vs.get(6), -10.0f, 21.0f, 1.0f, 1.0f, 0);
        assertVertex(vs.get(7), -10.0f, 19.0f, 1.0f, 0.0f, 0);
        for (final CampaignEngineGlowRenderHelper.EncodedVertex v : vs) {
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
        assertEquals(-2.0f, CampaignEngineGlowRenderHelper.vertexAt(2).y(), 0.0f);

        encodeOptimized(List.of(slot), color, 1.0f, 1.0f, 1.0f, 2.0f, 1.0f);
        assertEquals(-4.0f, CampaignEngineGlowRenderHelper.vertexAt(2).y(), 0.0f);
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
                assertEquals(expected.size(), CampaignEngineGlowRenderHelper.getNumVertices(),
                        prefix + "顶点数");
                for (int i = 0; i < expected.size(); i++) {
                    RecordedVertex e = expected.get(i);
                    CampaignEngineGlowRenderHelper.EncodedVertex a =
                            CampaignEngineGlowRenderHelper.vertexAt(i);
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
        List<CampaignEngineGlowRenderHelper.EncodedVertex> firstPass = vertices(
                CampaignEngineGlowRenderHelper.getNumVertices());

        encodeOptimized(slots, cache, color, 1.0f, 1.0f, 3.0f, 1.0f, 2.0f);
        assertEquals(firstPass.size(), CampaignEngineGlowRenderHelper.getNumVertices());
        for (int i = 0; i < firstPass.size(); i++) {
            CampaignEngineGlowRenderHelper.EncodedVertex e = firstPass.get(i);
            CampaignEngineGlowRenderHelper.EncodedVertex a =
                    CampaignEngineGlowRenderHelper.vertexAt(i);
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
        assertEquals(expected.size(), CampaignEngineGlowRenderHelper.getNumVertices());
        for (int i = 0; i < expected.size(); i++) {
            RecordedVertex e = expected.get(i);
            CampaignEngineGlowRenderHelper.EncodedVertex a =
                    CampaignEngineGlowRenderHelper.vertexAt(i);
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

            assertEquals(expected.size(), CampaignEngineGlowRenderHelper.getNumVertices(),
                    "frame " + frame + " 顶点数");
            for (int i = 0; i < expected.size(); i++) {
                RecordedVertex e = expected.get(i);
                CampaignEngineGlowRenderHelper.EncodedVertex a =
                        CampaignEngineGlowRenderHelper.vertexAt(i);
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
        assertEquals(0, CampaignEngineGlowRenderHelper.getNumVertices());
    }

    @Test
    void hitGlowBatchKeepsColorHoistAndPerSlotSetSize() {
        List<FakeSlot> slots = List.of(
                new FakeSlot(180.0f, 10.0f, 6.0f, 4.0f, new Vector2f(1.0f, 2.0f)),
                new FakeSlot(180.0f, 8.0f, 3.5f, 2.0f, new Vector2f(-3.0f, 4.0f)));
        TrackingSprite hitGlow = new TrackingSprite(0.0f, 0.0f, 1.0f, 1.0f);
        Color glowColor = new Color(10, 20, 30, 200);
        float sizeScale = 1.25f;
        float glowSizeMult = 0.8f;

        byte r = (byte) glowColor.getRed();
        byte g = (byte) glowColor.getGreen();
        byte b = (byte) glowColor.getBlue();
        byte a = (byte) (int) (glowColor.getAlpha() * 1.0f);
        CampaignEngineGlowRenderHelper.encodeHitGlowQuads(
                hitGlow, slots, sizeScale, glowSizeMult, true, r, g, b, a);

        assertEquals(0, hitGlow.renderAtCenterCalls, "合批路径不再逐槽 renderAtCenter");
        assertEquals(slots.size(), hitGlow.sizes.size());
        for (int i = 0; i < slots.size(); i++) {
            float expectedSize = slots.get(i).glowSize * sizeScale * glowSizeMult;
            assertEquals(Float.floatToRawIntBits(expectedSize),
                    Float.floatToRawIntBits(hitGlow.sizes.get(i)), "slot " + i + " size");
        }

        TrackingSprite untouched = new TrackingSprite(0.0f, 0.0f, 1.0f, 1.0f);
        CampaignEngineGlowRenderHelper.renderHitGlowSprites(
                untouched, List.of(), glowColor, sizeScale, glowSizeMult, 1.0f);
        assertEquals(0, untouched.setColorCalls);
        assertTrue(untouched.sizes.isEmpty());
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
            TrackingSprite hitGlow = new TrackingSprite(texX, texY, texWidth, texHeight);
            Color glowColor = new Color(random.nextInt(256), random.nextInt(256),
                    random.nextInt(256), random.nextInt(256));
            float sizeScale = 0.5f + random.nextFloat();
            float glowSizeMult = 0.5f + random.nextFloat();
            float alphaMult = random.nextFloat() * 1.5f;

            byte r = (byte) glowColor.getRed();
            byte g = (byte) glowColor.getGreen();
            byte b = (byte) glowColor.getBlue();
            byte a = (byte) (int) (glowColor.getAlpha() * alphaMult);
            CampaignEngineGlowRenderHelper.encodeHitGlowQuads(
                    hitGlow, slots, sizeScale, glowSizeMult, true, r, g, b, a);

            List<RecordedVertex> expected = new ArrayList<>();
            referenceHitGlow(slots, sizeScale, glowSizeMult, alphaMult,
                    texX, texY, texWidth, texHeight, glowColor, expected);

            String prefix = "trial " + trial + " ";
            assertEquals(expected.size(), CampaignEngineGlowRenderHelper.getNumVertices(),
                    prefix + "顶点数");
            for (int i = 0; i < expected.size(); i++) {
                RecordedVertex e = expected.get(i);
                CampaignEngineGlowRenderHelper.EncodedVertex v =
                        CampaignEngineGlowRenderHelper.vertexAt(i);
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
    void hitGlowWithoutTextureKeepsSetSizeButEncodesNothing() {
        List<FakeSlot> slots = List.of(
                new FakeSlot(180.0f, 10.0f, 6.0f, 4.0f, new Vector2f(1.0f, 2.0f)),
                new FakeSlot(180.0f, 8.0f, 3.5f, 2.0f, new Vector2f(-3.0f, 4.0f)));
        TrackingSprite hitGlow = new TrackingSprite(0.0f, 0.0f, 1.0f, 1.0f);
        Color glowColor = new Color(10, 20, 30, 200);

        CampaignEngineGlowRenderHelper.renderHitGlowSprites(
                hitGlow, slots, glowColor, 1.25f, 0.8f, 1.0f);

        assertEquals(1, hitGlow.setColorCalls);
        assertSame(glowColor, hitGlow.lastColor, "颜色合并为循环外一次 setColor");
        assertEquals(slots.size(), hitGlow.sizes.size(), "setSize 状态写回保留");
        assertEquals(0, CampaignEngineGlowRenderHelper.getNumVertices(), "不编码不绘制");
    }

    @Test
    void hitGlowRendersForFleetInsideLodMargin() {
        RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        Vector2f location = new Vector2f(500f, 500f);

        assertFalse(CampaignEngineGlowRenderHelper.shouldSkipHitGlow(true, location, viewport));
        assertEquals(1, viewport.nearChecks);
        assertSame(location, viewport.lastLocation);
        assertEquals(CampaignEngineGlowRenderHelper.HIT_GLOW_LOD_MARGIN, viewport.lastMargin);
    }

    @Test
    void hitGlowSkippedForFleetFarBeyondLodMargin() {
        RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        assertTrue(CampaignEngineGlowRenderHelper.shouldSkipHitGlow(
                true, new Vector2f(100000f, 100000f), viewport));
    }

    @Test
    void hitGlowRendersJustInsideLodMarginBoundary() {
        RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        float margin = CampaignEngineGlowRenderHelper.HIT_GLOW_LOD_MARGIN;
        assertFalse(CampaignEngineGlowRenderHelper.shouldSkipHitGlow(
                true, new Vector2f(1000f + margin - 1f, 500f), viewport));
    }

    @Test
    void hitGlowSkippedJustBeyondLodMarginBoundary() {
        RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        float margin = CampaignEngineGlowRenderHelper.HIT_GLOW_LOD_MARGIN;
        assertTrue(CampaignEngineGlowRenderHelper.shouldSkipHitGlow(
                true, new Vector2f(1000f + margin + 1f, 500f), viewport));
    }

    @Test
    void hitGlowLodNotAppliedForFleetOutsideCurrentLocation() {
        RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        assertFalse(CampaignEngineGlowRenderHelper.shouldSkipHitGlow(
                false, new Vector2f(100000f, 100000f), viewport));
        assertEquals(0, viewport.nearChecks);
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

    private static final class TrackingSprite extends Sprite implements SpriteUvAccessor {
        private final float texX;
        private final float texY;
        private final float texWidth;
        private final float texHeight;
        private int         setColorCalls;
        private Color       lastColor;
        private int         renderAtCenterCalls;
        private final List<Float> sizes = new ArrayList<>();

        private TrackingSprite(final float texX, final float texY,
                               final float texWidth, final float texHeight) {
            this.texX = texX;
            this.texY = texY;
            this.texWidth = texWidth;
            this.texHeight = texHeight;
        }

        @Override
        public void setColor(final Color color) {
            setColorCalls++;
            lastColor = color;
        }

        @Override
        public void setSize(final float width, final float height) {
            assertEquals(Float.floatToRawIntBits(width), Float.floatToRawIntBits(height),
                    "hitGlow 尺寸恒为正方形");
            sizes.add(width);
        }

        @Override
        public void renderAtCenter(final float x, final float y) {
            renderAtCenterCalls++;
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

    private static final class RectViewport implements ViewportAPI {
        private final float llx;
        private final float lly;
        private final float width;
        private final float height;
        private       int   nearChecks;
        private       Vector2f lastLocation;
        private       float    lastMargin;

        private RectViewport(final float llx, final float lly, final float width, final float height) {
            this.llx = llx;
            this.lly = lly;
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean isNearViewport(final Vector2f loc, final float margin) {
            nearChecks++;
            lastLocation = loc;
            lastMargin = margin;
            return loc.x >= llx - margin && loc.x <= llx + width + margin
                    && loc.y >= lly - margin && loc.y <= lly + height + margin;
        }

        @Override
        public Vector2f getCenter() {
            return new Vector2f(llx + width / 2f, lly + height / 2f);
        }

        @Override
        public float getLLX() {
            return llx;
        }

        @Override
        public float getLLY() {
            return lly;
        }

        @Override
        public float getVisibleWidth() {
            return width;
        }

        @Override
        public float getVisibleHeight() {
            return height;
        }

        @Override
        public float getWorldXtoScreenX() {
            return 1f;
        }

        @Override
        public float getWorldYtoScreenY() {
            return 1f;
        }

        @Override
        public float getViewMult() {
            return 1f;
        }

        @Override
        public float getAlphaMult() {
            return 1f;
        }

        @Override
        public float convertScreenXToWorldX(final float x) {
            return x;
        }

        @Override
        public float convertScreenYToWorldY(final float y) {
            return y;
        }

        @Override
        public float convertWorldXtoScreenX(final float x) {
            return x;
        }

        @Override
        public float convertWorldYtoScreenY(final float y) {
            return y;
        }

        @Override
        public float convertWorldWidthToScreenWidth(final float w) {
            return w;
        }

        @Override
        public float convertWorldHeightToScreenHeight(final float h) {
            return h;
        }

        @Override
        public float convertScreenWidthToWorldWidth(final float w) {
            return w;
        }

        @Override
        public float convertScreenHeightToWorldHeight(final float h) {
            return h;
        }

        @Override
        public void set(final float llx, final float lly, final float width, final float height) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setViewMult(final float viewMult) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isExternalControl() {
            return false;
        }

        @Override
        public void setExternalControl(final boolean externalControl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCenter(final Vector2f center) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAlphaMult(final float alphaMult) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEverythingNearViewport() {
            return false;
        }

        @Override
        public void setEverythingNearViewport(final boolean everythingNearViewport) {
            throw new UnsupportedOperationException();
        }
    }

    private static void encodeOptimized(final List<FakeSlot> slots,
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

    private static void encodeOptimized(final List<FakeSlot> slots,
                                        final CampaignEngineGlowRenderHelper.GlowGeometryCache cache,
                                        final Color color,
                                        final float alphaMult,
                                        final float fullBrightness,
                                        final float lengthScale,
                                        final float widthMultCurr,
                                        final float heightMultCurr) {
        CampaignEngineGlowRenderHelper.encodeSlots(
                slots, cache, widthMultCurr, heightMultCurr, fullBrightness, lengthScale,
                (byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(),
                (byte) (color.getAlpha() * 0.0f), (byte) (color.getAlpha() * alphaMult));
    }

    private static List<CampaignEngineGlowRenderHelper.EncodedVertex> vertices(final int count) {
        List<CampaignEngineGlowRenderHelper.EncodedVertex> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(CampaignEngineGlowRenderHelper.vertexAt(i));
        }
        return out;
    }

    private static void assertVertex(final CampaignEngineGlowRenderHelper.EncodedVertex v,
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
