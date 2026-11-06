package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.combat.entities.ContrailEngine;
import com.fs.starfarer.loading.specs.EngineSlot;
import com.fs.util.VectorMathUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CampaignContrailBatchHelperAgedPathTest {

    @BeforeEach
    void resetBatch() {
        CampaignContrailBatchHelper.beginStrip();
    }

    @Test
    void agedZeroBrightnessPointEmitsZeroAlphaWithFullGeometry() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(255, 128, 64, 200));
        contrail.points.add(point(0f, 0f, 0f, 1f, 2f, 3f, 0f, 0f));
        ContrailEngineV2.ContrailPoint aged = point(10f, 0f, 0f, 1f, 4f, 3f, 3f, 0.5f);
        aged.fadeOut = true;
        aged.origMax = 0.8f;
        aged.maxBrightness = 0.0f;
        aged.elapsedWhenFadeOut = 1f;
        aged.lastProximityMult = 0.6f;
        contrail.points.add(aged);

        ContrailEngineV2.ContrailPoint fadeSource =
                CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);

        assertSame(aged, fadeSource, "简化路径的 fadeOut 点仍须成为 fadeSource");
        assertEquals(0.0f, aged.lastProximityMult, 1e-6f,
                "fadeSource==本点 ⇒ mult 恒 0，min 钳制写回 0");
        CampaignContrailBatchHelper.EncodedVertex left = CampaignContrailBatchHelper.vertexAt(2);
        assertEquals(10f, left.x(), 1e-4f);
        assertEquals(-2f, left.y(), 1e-4f);
        assertEquals(0.5f, left.u(), 1e-4f);
        assertEquals(0.01f, left.v(), 1e-4f);
        assertEquals(255, left.r());
        assertEquals(0, left.a(), "老化零亮度点 alpha 字节恒 0");
        CampaignContrailBatchHelper.EncodedVertex right = CampaignContrailBatchHelper.vertexAt(3);
        assertEquals(2f, right.y(), 1e-4f);
        assertEquals(0.99f, right.v(), 1e-4f);
        assertEquals(0, right.a());
    }

    @Test
    void simplifiedPathKeepsLastProximityMultClampSemantics() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(1, 2, 3, 255));
        ContrailEngineV2.ContrailPoint negative = point(0f, 0f, 0f, 1f, 2f, 3f, 3f, 0f);
        negative.fadeOut = true;
        negative.maxBrightness = 0.0f;
        negative.lastProximityMult = -0.5f;
        ContrailEngineV2.ContrailPoint positive = point(10f, 0f, 0f, 1f, 2f, 3f, 3f, 0.5f);
        positive.fadeOut = true;
        positive.maxBrightness = 0.0f;
        positive.lastProximityMult = 0.7f;
        contrail.points.add(negative);
        contrail.points.add(positive);

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);

        assertEquals(Float.floatToRawIntBits(-0.5f),
                Float.floatToRawIntBits(negative.lastProximityMult),
                "原版 0 > lastProximityMult(-0.5) ⇒ 维持 -0.5");
        assertEquals(0.0f, positive.lastProximityMult, "0.7 ⇒ 钳为 0");
    }

    @Test
    void fadingPointStillUsesFullPath() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(10, 20, 30, 255));
        contrail.points.add(point(0f, 0f, 0f, 1f, 2f, 3f, 0f, 0f));
        ContrailEngineV2.ContrailPoint fading = point(10f, 0f, 0f, 1f, 2f, 3f, 1f, 0.5f);
        fading.fadeOut = true;
        fading.origMax = 1f;
        fading.maxBrightness = 0.5f;
        fading.elapsedWhenFadeOut = 1f;
        contrail.points.add(fading);

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);

        assertEquals(43, CampaignContrailBatchHelper.vertexAt(2).a(),
                "衰减中的 fadeOut 点必须走完整路径（亮度公式 + proximity）");
    }

    @Test
    void fadeSourcePropagatesThroughSimplifiedPointAcrossContrails() {
        ContrailEngineV2.Contrail first = contrail(new Color(10, 20, 30, 255));
        first.points.add(point(0f, 0f, 0f, 1f, 2f, 3f, 0f, 0f));
        ContrailEngineV2.ContrailPoint agedTail = point(10f, 0f, 0f, 1f, 2f, 3f, 3f, 0.5f);
        agedTail.fadeOut = true;
        agedTail.origMax = 0.8f;
        agedTail.maxBrightness = 0.0f;
        agedTail.elapsedWhenFadeOut = 1f;
        agedTail.lastProximityMult = 0.5f;
        first.points.add(agedTail);

        ContrailEngineV2.Contrail second = contrail(new Color(10, 20, 30, 255));
        second.points.add(point(300f, 0f, 0f, 1f, 2f, 3f, 0f, 0f));
        ContrailEngineV2.ContrailPoint near = point(10f, 0.5f, 0f, 1f, 2f, 3f, 2f, 0.75f);
        near.maxBrightness = 1f;
        second.points.add(near);

        ContrailEngineV2.ContrailPoint fadeSource =
                CampaignContrailBatchHelper.encodeContrail(first, 1f, null);
        assertSame(agedTail, fadeSource);
        fadeSource = CampaignContrailBatchHelper.encodeContrail(second, 1f, fadeSource);
        assertSame(agedTail, fadeSource, "第二条尾迹无 fadeOut 点时参照不变");

        assertEquals(0.01f, near.lastProximityMult, 1e-6f,
                "活跃点 proximity 仍以简化路径的 fadeOut 点为参照");
        assertEquals(0, CampaignContrailBatchHelper.vertexAt(8).a());
    }

    @Test
    void encodedOutputMatchesReferenceBitwiseAcrossAgedStates() {
        Random random = new Random(0xA6ED_00L);
        for (int trial = 0; trial < 400; trial++) {
            CampaignContrailBatchHelper.beginStrip();

            int contrailCount = 1 + random.nextInt(4);
            List<ContrailEngineV2.Contrail> reference = new ArrayList<>();
            List<ContrailEngineV2.Contrail> optimized = new ArrayList<>();
            for (int c = 0; c < contrailCount; c++) {
                ContrailEngineV2.Contrail contrail = agingContrail(random);
                reference.add(contrail);
                optimized.add(copyOf(contrail));
            }
            float alphaMult = random.nextFloat() * 2f;

            List<RecordedVertex> expected = new ArrayList<>();
            ContrailEngineV2.ContrailPoint refFadeSource = null;
            for (ContrailEngineV2.Contrail contrail : reference) {
                if (!expected.isEmpty()) {
                    RecordedVertex last = expected.get(expected.size() - 1);
                    expected.add(last);
                    expected.add(last);
                }
                refFadeSource = referenceEncodeContrail(contrail, alphaMult, refFadeSource, expected);
            }

            ContrailEngineV2.ContrailPoint optFadeSource = null;
            for (ContrailEngineV2.Contrail contrail : optimized) {
                optFadeSource = CampaignContrailBatchHelper.encodeContrail(contrail, alphaMult, optFadeSource);
            }

            String prefix = "trial " + trial + " ";
            assertEquals(expected.size(), CampaignContrailBatchHelper.getNumVertices(), prefix + "顶点数");
            for (int i = 0; i < expected.size(); i++) {
                RecordedVertex e = expected.get(i);
                CampaignContrailBatchHelper.EncodedVertex a = CampaignContrailBatchHelper.vertexAt(i);
                assertEquals(Float.floatToRawIntBits(e.x), Float.floatToRawIntBits(a.x()), prefix + "v" + i + " x");
                assertEquals(Float.floatToRawIntBits(e.y), Float.floatToRawIntBits(a.y()), prefix + "v" + i + " y");
                assertEquals(Float.floatToRawIntBits(e.u), Float.floatToRawIntBits(a.u()), prefix + "v" + i + " u");
                assertEquals(Float.floatToRawIntBits(e.v), Float.floatToRawIntBits(a.v()), prefix + "v" + i + " v");
                assertEquals(e.r, a.r(), prefix + "v" + i + " r");
                assertEquals(e.g, a.g(), prefix + "v" + i + " g");
                assertEquals(e.b, a.b(), prefix + "v" + i + " b");
                assertEquals(e.a, a.a(), prefix + "v" + i + " a");
            }

            for (int c = 0; c < contrailCount; c++) {
                List<ContrailEngineV2.ContrailPoint> refPoints = reference.get(c).points;
                List<ContrailEngineV2.ContrailPoint> optPoints = optimized.get(c).points;
                assertEquals(refPoints.size(), optPoints.size(), prefix + "点数");
                for (int i = 0; i < refPoints.size(); i++) {
                    ContrailEngineV2.ContrailPoint rp = refPoints.get(i);
                    ContrailEngineV2.ContrailPoint op = optPoints.get(i);
                    String pp = prefix + "contrail " + c + " point " + i + " ";
                    assertEquals(rp.fadeOut, op.fadeOut, pp + "fadeOut");
                    assertEquals(Float.floatToRawIntBits(rp.origMax),
                            Float.floatToRawIntBits(op.origMax), pp + "origMax");
                    assertEquals(Float.floatToRawIntBits(rp.elapsedWhenFadeOut),
                            Float.floatToRawIntBits(op.elapsedWhenFadeOut), pp + "elapsedWhenFadeOut");
                    assertEquals(Float.floatToRawIntBits(rp.lastProximityMult),
                            Float.floatToRawIntBits(op.lastProximityMult), pp + "lastProximityMult");
                    assertEquals(Float.floatToRawIntBits(rp.maxBrightness),
                            Float.floatToRawIntBits(op.maxBrightness), pp + "maxBrightness");
                }
            }
        }
    }


    private static ContrailEngineV2.ContrailPoint referenceEncodeContrail(
            ContrailEngineV2.Contrail contrail, float alphaMult,
            ContrailEngineV2.ContrailPoint fadeSource, List<RecordedVertex> out) {
        ContrailEngineV2.ContrailPoint var26 = null;
        float var9 = 0.0f;
        for (ContrailEngineV2.ContrailPoint var10 : contrail.points) {
            ContrailEngineV2.ContrailPoint var27;
            if (contrail.points.size() > ++var9) {
                var27 = contrail.points.get((int) var9);
            } else {
                var27 = null;
            }

            float var12 = 0.0f;
            float var13 = 0.1f;
            float var14 = var10.elapsed;
            if (var14 > var10.elapsedWhenFadeOut) {
                var14 = var10.elapsedWhenFadeOut;
            }
            if (var13 > var10.duration / 2.0f) {
                var13 = var10.duration / 2.0f;
            }
            if (var14 < var13) {
                var12 = var14 / var13;
            } else {
                var12 = (var10.duration - var14) / (var10.duration - var13);
            }
            if (var12 > 1.0f) {
                var12 = 1.0f;
            }
            var12 *= alphaMult;
            Color var15 = contrail.color;
            if (var27 != null) {
                Vector2f var16 = new Vector2f(var10.perp);
                var16.scale(var10.width / 2.0f * 1.5f);
                Vector2f var17 = Vector2f.add(var10.point, var16, new Vector2f());
                var16 = Vector2f.sub(var10.point, var16, var16);
                Vector2f var18 = new Vector2f(var27.perp);
                var18.scale(var27.width / 2.0f * 1.5f);
                Vector2f var19 = Vector2f.add(var27.point, var18, new Vector2f());
                var18 = Vector2f.sub(var27.point, var18, var18);
                Vector2f var20 = VectorMathUtils.segmentIntersection(var16, var17, var18, var19);
                if (var20 != null) {
                    if (!var10.fadeOut) {
                        var10.origMax = var10.maxBrightness;
                        var10.elapsedWhenFadeOut = var10.elapsed;
                    }
                    var10.fadeOut = true;
                } else if (var26 != null) {
                    Vector2f var21 = new Vector2f(var26.perp);
                    var21.scale(var26.width / 2.0f * 1.5f);
                    Vector2f var22 = Vector2f.add(var26.point, var21, new Vector2f());
                    var21 = Vector2f.sub(var26.point, var21, var21);
                    var20 = VectorMathUtils.segmentIntersection(var16, var17, var21, var22);
                    if (var20 != null) {
                        if (!var10.fadeOut) {
                            var10.origMax = var10.maxBrightness;
                            var10.elapsedWhenFadeOut = var10.elapsed;
                        }
                        var10.fadeOut = true;
                    }
                    if (var12 > 0.0f) {
                        Vector2f var23 = Vector2f.sub(var26.point, var10.point, new Vector2f());
                        Vector2f var24 = Vector2f.sub(var27.point, var10.point, new Vector2f());
                        if (Vector2f.dot(var23, var24) > 0.0f) {
                            if (!var10.fadeOut) {
                                var10.origMax = var10.maxBrightness;
                                var10.elapsedWhenFadeOut = var10.elapsed;
                            }
                            var10.fadeOut = true;
                        }
                    }
                }
            }

            if (var9 == 1.0f) {
                var12 = 0.0f;
            }
            if (var10.fadeOut) {
                fadeSource = var10;
            }
            if (fadeSource != null) {
                float dx = var10.point.x - fadeSource.point.x;
                float dy = var10.point.y - fadeSource.point.y;
                float var32 = (float) Math.sqrt(dx * dx + dy * dy);
                float var33 = 50.0f;
                float var35 = 1.0f;
                if (fadeSource.origMax > 0.0f) {
                    var35 = 1.0f - fadeSource.maxBrightness / fadeSource.origMax;
                }
                float var36 = 1.0f - var35 + var35 * Math.min(1.0f, var32 / var33);
                if (var36 > var10.lastProximityMult) {
                    var36 = var10.lastProximityMult;
                }
                var10.lastProximityMult = var36;
                var12 *= var36;
            }

            int r = var15.getRed();
            int g = var15.getGreen();
            int b = var15.getBlue();
            int a = ((byte) (int) (var15.getAlpha() * var10.maxBrightness * var12)) & 0xFF;
            out.add(new RecordedVertex(
                    var10.point.x - var10.perp.x * var10.width / 2.0f,
                    var10.point.y - var10.perp.y * var10.width / 2.0f,
                    var10.texCoord, 0.01f, r, g, b, a));
            out.add(new RecordedVertex(
                    var10.point.x + var10.perp.x * var10.width / 2.0f,
                    var10.point.y + var10.perp.y * var10.width / 2.0f,
                    var10.texCoord, 0.99f, r, g, b, a));
            var26 = var10;
        }

        if (contrail.lastPoint != null && var26 != null) {
            int r = contrail.color.getRed();
            int g = contrail.color.getGreen();
            int b = contrail.color.getBlue();
            int a = ((byte) (contrail.color.getAlpha() * 0.0f)) & 0xFF;
            out.add(new RecordedVertex(
                    contrail.lastPoint.x - var26.perp.x * var26.width / 4.0f,
                    contrail.lastPoint.y - var26.perp.y * var26.width / 4.0f,
                    var26.texCoord, 0.01f, r, g, b, a));
            out.add(new RecordedVertex(
                    contrail.lastPoint.x + var26.perp.x * var26.width / 4.0f,
                    contrail.lastPoint.y + var26.perp.y * var26.width / 4.0f,
                    var26.texCoord, 0.99f, r, g, b, a));
        }
        return fadeSource;
    }

    private record RecordedVertex(float x, float y, float u, float v, int r, int g, int b, int a) {
    }

    private static ContrailEngineV2.Contrail contrail(Color color) {
        ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        contrail.color = color;
        contrail.blendMode = EngineSlot.BlendMode.GLOW;
        contrail.mode = ContrailEngine.ContrailWidthMode.WIDEN;
        return contrail;
    }

    private static ContrailEngineV2.ContrailPoint point(float x, float y, float perpX, float perpY,
                                                        float width, float duration, float elapsed,
                                                        float texCoord) {
        ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
        point.point = new Vector2f(x, y);
        point.perp = new Vector2f(perpX, perpY);
        point.vel = new Vector2f();
        point.width = width;
        point.maxWidth = width;
        point.duration = duration;
        point.elapsed = elapsed;
        point.texCoord = texCoord;
        point.maxBrightness = 1f;
        return point;
    }

    private static ContrailEngineV2.Contrail agingContrail(Random random) {
        ContrailEngineV2.Contrail contrail = contrail(new Color(
                random.nextInt(256), random.nextInt(256), random.nextInt(256),
                1 + random.nextInt(255)));
        contrail.lastPoint = random.nextBoolean()
                ? null
                : new Vector2f(random.nextFloat() * 40f, random.nextFloat() * 40f);

        int pointCount = 2 + random.nextInt(6);
        float x = random.nextFloat() * 20f;
        float y = random.nextFloat() * 20f;
        float texCoord = 0f;
        for (int i = 0; i < pointCount; i++) {
            ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
            x += random.nextFloat() * 12f - 6f;
            y += random.nextFloat() * 12f - 6f;
            point.point = new Vector2f(x, y);
            point.perp = new Vector2f(random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f);
            point.vel = new Vector2f();
            point.width = random.nextFloat() * 30f;
            point.maxWidth = point.width;
            point.duration = switch (random.nextInt(8)) {
                case 0 -> 0f;
                case 1 -> 0.05f;
                default -> 0.5f + random.nextFloat() * 3f;
            };
            point.elapsed = random.nextFloat() * 4f;
            texCoord += random.nextFloat() * 0.5f;
            point.texCoord = texCoord;
            switch (random.nextInt(6)) {
                case 0, 1, 2 -> {
                    point.fadeOut = true;
                    point.origMax = random.nextInt(4) == 0 ? 0.0f : random.nextFloat() * 1.5f;
                    point.maxBrightness = 0.0f;
                    point.elapsedWhenFadeOut = random.nextFloat() * 3f;
                    point.lastProximityMult = random.nextFloat() * 2f - 1f;
                }
                case 3 -> {
                    point.fadeOut = true;
                    point.origMax = random.nextFloat() * 1.5f;
                    point.maxBrightness = random.nextFloat();
                    point.elapsedWhenFadeOut = random.nextFloat() * 3f;
                    point.lastProximityMult = random.nextFloat();
                }
                default -> point.maxBrightness = random.nextFloat() * 1.5f;
            }
            contrail.points.add(point);
        }
        return contrail;
    }

    private static ContrailEngineV2.Contrail copyOf(ContrailEngineV2.Contrail src) {
        ContrailEngineV2.Contrail copy = contrail(src.color);
        copy.lastPoint = src.lastPoint == null ? null : new Vector2f(src.lastPoint);
        for (ContrailEngineV2.ContrailPoint p : src.points) {
            ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
            point.point = new Vector2f(p.point);
            point.perp = new Vector2f(p.perp);
            point.vel = new Vector2f(p.vel);
            point.width = p.width;
            point.maxWidth = p.maxWidth;
            point.duration = p.duration;
            point.elapsed = p.elapsed;
            point.texCoord = p.texCoord;
            point.maxBrightness = p.maxBrightness;
            point.fadeOut = p.fadeOut;
            point.origMax = p.origMax;
            point.elapsedWhenFadeOut = p.elapsedWhenFadeOut;
            point.lastProximityMult = p.lastProximityMult;
            point.progress = p.progress;
            point.distToPrev = p.distToPrev;
            copy.points.add(point);
        }
        return copy;
    }
}
