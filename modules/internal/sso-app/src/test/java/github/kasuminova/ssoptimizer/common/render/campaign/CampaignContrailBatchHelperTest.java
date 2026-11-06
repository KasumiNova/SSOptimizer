package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.combat.entities.ContrailEngine;
import com.fs.util.VectorMathUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CampaignContrailBatchHelperTest {

    @BeforeEach
    void resetBatch() {
        CampaignContrailBatchHelper.beginStrip();
    }

    @Test
    void segmentsIntersectMatchesVectorMathUtilsAcrossRandomStates() {
        Random random = new Random(0xC0FF_EEL);
        for (int trial = 0; trial < 20_000; trial++) {
            float ax = randCoord(random), ay = randCoord(random);
            float bx = randCoord(random), by = randCoord(random);
            float cx = randCoord(random), cy = randCoord(random);
            float dx = randCoord(random), dy = randCoord(random);
            boolean expected = VectorMathUtils.segmentIntersection(
                    new Vector2f(ax, ay), new Vector2f(bx, by),
                    new Vector2f(cx, cy), new Vector2f(dx, dy)) != null;
            assertEquals(expected,
                    CampaignContrailBatchHelper.segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy),
                    "trial " + trial);
        }
    }

    private static float randCoord(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> 0f;
            case 1 -> random.nextFloat() * 4f - 2f;
            default -> random.nextFloat() * 200f - 100f;
        };
    }

    @Test
    void singleContrailEncodesPointPairsThenTailPair() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(255, 128, 64, 200));
        contrail.points.add(point(10f, 20f, 1f, 0f, 4f, 2f, 1f, 0.5f));
        contrail.points.add(point(30f, 20f, 1f, 0f, 4f, 2f, 1f, 1.5f));
        contrail.lastPoint = new Vector2f(40f, 20f);

        assertNull(CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null));
        assertEquals(6, CampaignContrailBatchHelper.getNumVertices());

        CampaignContrailBatchHelper.EncodedVertex v0 = CampaignContrailBatchHelper.vertexAt(0);
        assertEquals(8f, v0.x(), 1e-4f, "point(10,20) - perp(1,0) × width/2(2)");
        assertEquals(20f, v0.y(), 1e-4f);
        assertEquals(0.5f, v0.u(), 1e-4f);
        assertEquals(0.01f, v0.v(), 1e-4f);
        assertEquals(0, v0.a(), "首点 alpha 恒 0");
        CampaignContrailBatchHelper.EncodedVertex v1 = CampaignContrailBatchHelper.vertexAt(1);
        assertEquals(12f, v1.x(), 1e-4f);
        assertEquals(0.99f, v1.v(), 1e-4f);
        assertEquals(0, v1.a());

        CampaignContrailBatchHelper.EncodedVertex v2 = CampaignContrailBatchHelper.vertexAt(2);
        assertEquals(28f, v2.x(), 1e-4f);
        assertEquals(1.5f, v2.u(), 1e-4f);
        assertEquals(105, v2.a());
        assertEquals(255, v2.r());
        assertEquals(128, v2.g());
        assertEquals(64, v2.b());

        CampaignContrailBatchHelper.EncodedVertex v4 = CampaignContrailBatchHelper.vertexAt(4);
        assertEquals(39f, v4.x(), 1e-4f);
        assertEquals(20f, v4.y(), 1e-4f);
        assertEquals(1.5f, v4.u(), 1e-4f);
        assertEquals(0.01f, v4.v(), 1e-4f);
        assertEquals(0, v4.a());
        CampaignContrailBatchHelper.EncodedVertex v5 = CampaignContrailBatchHelper.vertexAt(5);
        assertEquals(41f, v5.x(), 1e-4f);
        assertEquals(0.99f, v5.v(), 1e-4f);
        assertEquals(0, v5.a());
    }

    @Test
    void fadeInPointAlphaUsesElapsedOverFadeWindow() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(10, 20, 30, 255));
        contrail.points.add(point(0f, 0f, 0f, 1f, 2f, 2f, 0f, 0f));
        contrail.points.add(point(10f, 0f, 0f, 1f, 2f, 2f, 0.05f, 0.25f));

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);
        assertEquals(127, CampaignContrailBatchHelper.vertexAt(2).a());
    }

    @Test
    void alphaMultScalesPointBrightness() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(10, 20, 30, 255));
        contrail.points.add(point(0f, 0f, 0f, 1f, 2f, 2f, 0f, 0f));
        contrail.points.add(point(10f, 0f, 0f, 1f, 2f, 2f, 0.05f, 0.25f));

        CampaignContrailBatchHelper.encodeContrail(contrail, 0.5f, null);
        assertEquals(63, CampaignContrailBatchHelper.vertexAt(2).a());
    }

    @Test
    void contrailWithoutLastPointEmitsNoTailPair() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(1, 2, 3, 255));
        contrail.points.add(point(0f, 0f, 1f, 0f, 2f, 2f, 0f, 0f));
        contrail.points.add(point(10f, 0f, 1f, 0f, 2f, 2f, 1f, 0.5f));
        contrail.lastPoint = null;

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);
        assertEquals(4, CampaignContrailBatchHelper.getNumVertices());
    }

    @Test
    void intersectionWithNextMarksFadeOutAndSnapshotsState() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(10, 20, 30, 255));
        contrail.points.add(point(0f, 0f, 0f, 1f, 4f, 3f, 0f, 0f));
        ContrailEngineV2.ContrailPoint p1 = point(10f, 0f, 0f, 1f, 40f, 3f, 1f, 0.5f);
        p1.maxBrightness = 0.8f;
        contrail.points.add(p1);
        contrail.points.add(point(10f, 1f, 0f, 1f, 40f, 3f, 1f, 1f));

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);
        assertTrue(p1.fadeOut, "展开段相交应标记 fadeOut");
        assertEquals(0.8f, p1.origMax, 1e-6f, "首次标记时快照 maxBrightness");
        assertEquals(1f, p1.elapsedWhenFadeOut, 1e-6f, "首次标记时快照 elapsed");
    }

    @Test
    void proximityFadeAppliesAcrossContrailsWithMonotonicClamp() {
        ContrailEngineV2.Contrail first = contrail(new Color(10, 20, 30, 255));
        first.points.add(point(0f, 0f, 0f, 1f, 2f, 3f, 0f, 0f));
        ContrailEngineV2.ContrailPoint source = point(10f, 0f, 0f, 1f, 2f, 3f, 1f, 0.5f);
        source.fadeOut = true;
        source.origMax = 0.8f;
        source.maxBrightness = 0.4f;
        source.elapsedWhenFadeOut = 1f;
        first.points.add(source);
        first.points.add(point(20f, 1f, 0f, 1f, 2f, 3f, 1f, 1f));

        ContrailEngineV2.Contrail second = contrail(new Color(10, 20, 30, 255));
        second.points.add(point(300f, 0f, 0f, 1f, 2f, 3f, 0f, 0f));
        ContrailEngineV2.ContrailPoint far = point(200f, 1f, 0f, 1f, 2f, 3f, 2f, 0.5f);
        far.maxBrightness = 1f;
        second.points.add(far);
        ContrailEngineV2.ContrailPoint near = point(10f, 0.5f, 0f, 1f, 2f, 3f, 2f, 0.75f);
        near.maxBrightness = 1f;
        second.points.add(near);

        ContrailEngineV2.ContrailPoint fadeSource =
                CampaignContrailBatchHelper.encodeContrail(first, 1f, null);
        assertSame(source, fadeSource, "fadeOut 点应成为后续尾迹的 proximity 参照");
        fadeSource = CampaignContrailBatchHelper.encodeContrail(second, 1f, fadeSource);
        assertSame(source, fadeSource, "第二条尾迹无 fadeOut 点时参照不变");

        assertEquals(87, CampaignContrailBatchHelper.vertexAt(10).a());
        assertEquals(44, CampaignContrailBatchHelper.vertexAt(12).a());
        assertEquals(0.505f, near.lastProximityMult, 1e-3f, "lastProximityMult 写回");
        assertEquals(1f, far.lastProximityMult, 1e-6f, "远距离点不衰减");
    }

    @Test
    void joiningContrailsRepeatsLastVertexTwiceAsDegenerateConnector() {
        ContrailEngineV2.Contrail first = contrail(new Color(255, 128, 64, 200));
        first.points.add(point(10f, 20f, 1f, 0f, 4f, 2f, 0f, 0.5f));
        first.points.add(point(30f, 20f, 1f, 0f, 4f, 2f, 1f, 1.5f));
        first.lastPoint = new Vector2f(40f, 20f);

        ContrailEngineV2.Contrail second = contrail(new Color(10, 20, 30, 255));
        second.points.add(point(100f, 0f, 0f, 1f, 2f, 2f, 0f, 0.8f));
        second.points.add(point(120f, 0f, 0f, 1f, 2f, 2f, 1f, 1.8f));

        CampaignContrailBatchHelper.encodeContrail(first, 1f, null);
        CampaignContrailBatchHelper.encodeContrail(second, 1f, null);
        assertEquals(12, CampaignContrailBatchHelper.getNumVertices(), "6 + 2 连接点 + 4");

        CampaignContrailBatchHelper.EncodedVertex v5 = CampaignContrailBatchHelper.vertexAt(5);
        CampaignContrailBatchHelper.EncodedVertex v6 = CampaignContrailBatchHelper.vertexAt(6);
        CampaignContrailBatchHelper.EncodedVertex v7 = CampaignContrailBatchHelper.vertexAt(7);
        assertEquals(v5.x(), v6.x(), 1e-4f);
        assertEquals(v5.y(), v6.y(), 1e-4f);
        assertEquals(v5.u(), v6.u(), 1e-4f);
        assertEquals(v5.r(), v6.r());
        assertEquals(v5.g(), v6.g());
        assertEquals(v5.b(), v6.b());
        assertEquals(v5.a(), v6.a());
        assertEquals(v6.x(), v7.x(), 1e-4f);
        assertEquals(v6.y(), v7.y(), 1e-4f);

        CampaignContrailBatchHelper.EncodedVertex v8 = CampaignContrailBatchHelper.vertexAt(8);
        assertEquals(100f, v8.x(), 1e-4f);
        assertEquals(-1f, v8.y(), 1e-4f);
        assertEquals(0.8f, v8.u(), 1e-4f);
        assertEquals(0.01f, v8.v(), 1e-4f);
    }

    @Test
    void firstContrailOfBatchHasNoJoinConnector() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(1, 2, 3, 255));
        contrail.points.add(point(10f, 20f, 1f, 0f, 4f, 2f, 0f, 0.5f));
        contrail.points.add(point(30f, 20f, 1f, 0f, 4f, 2f, 1f, 1.5f));

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);
        assertEquals(4, CampaignContrailBatchHelper.getNumVertices());
        assertEquals(8f, CampaignContrailBatchHelper.vertexAt(0).x(), 1e-4f);
    }

    @Test
    void beginStripResetsBatchState() {
        ContrailEngineV2.Contrail first = contrail(new Color(1, 2, 3, 255));
        first.points.add(point(0f, 0f, 1f, 0f, 2f, 2f, 0f, 0.1f));
        first.points.add(point(5f, 0f, 1f, 0f, 2f, 2f, 1f, 0.2f));
        ContrailEngineV2.Contrail second = contrail(new Color(1, 2, 3, 255));
        second.points.add(point(10f, 0f, 0f, 1f, 2f, 2f, 0f, 0.3f));
        second.points.add(point(15f, 0f, 0f, 1f, 2f, 2f, 1f, 0.4f));

        CampaignContrailBatchHelper.encodeContrail(first, 1f, null);
        CampaignContrailBatchHelper.beginStrip();
        CampaignContrailBatchHelper.encodeContrail(second, 1f, null);

        assertEquals(4, CampaignContrailBatchHelper.getNumVertices(), "新批次首条无连接点");
        assertEquals(10f, CampaignContrailBatchHelper.vertexAt(0).x(), 1e-4f);
        assertEquals(-1f, CampaignContrailBatchHelper.vertexAt(0).y(), 1e-4f);
    }

    @Test
    void encodedOutputMatchesReferenceBitwiseAcrossRandomStates() {
        Random random = new Random(0xFACE_CAFEL);
        for (int trial = 0; trial < 300; trial++) {
            CampaignContrailBatchHelper.beginStrip();

            int contrailCount = 1 + random.nextInt(4);
            List<ContrailEngineV2.Contrail> reference = new ArrayList<>();
            List<ContrailEngineV2.Contrail> optimized = new ArrayList<>();
            for (int c = 0; c < contrailCount; c++) {
                ContrailEngineV2.Contrail contrail = randomContrail(random);
                reference.add(contrail);
                optimized.add(copyOf(contrail));
            }
            float alphaMult = random.nextFloat() * 2f;

            List<RecordedVertex> expected = new ArrayList<>();
            ContrailEngineV2.ContrailPoint refFadeSource = null;
            for (ContrailEngineV2.Contrail contrail : reference) {
                if (contrail.points.size() > 1) {
                    if (!expected.isEmpty()) {
                        RecordedVertex last = expected.get(expected.size() - 1);
                        expected.add(last);
                        expected.add(last);
                    }
                    refFadeSource = referenceEncodeContrail(contrail, alphaMult, refFadeSource, expected);
                } else {
                    for (ContrailEngineV2.ContrailPoint point : contrail.points) {
                        point.maxBrightness = 0.0f;
                    }
                }
            }

            ContrailEngineV2.ContrailPoint optFadeSource = null;
            for (ContrailEngineV2.Contrail contrail : optimized) {
                if (contrail.points.size() > 1) {
                    optFadeSource = CampaignContrailBatchHelper.encodeContrail(contrail, alphaMult, optFadeSource);
                } else {
                    for (ContrailEngineV2.ContrailPoint point : contrail.points) {
                        point.maxBrightness = 0.0f;
                    }
                }
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

    @Test
    void boundingCircleRejectionMatchesReferenceOnSparseContrails() {
        Random random = new Random(0xB0DC_1EL);
        for (int trial = 0; trial < 300; trial++) {
            CampaignContrailBatchHelper.beginStrip();

            int contrailCount = 1 + random.nextInt(3);
            List<ContrailEngineV2.Contrail> reference = new ArrayList<>();
            List<ContrailEngineV2.Contrail> optimized = new ArrayList<>();
            for (int c = 0; c < contrailCount; c++) {
                ContrailEngineV2.Contrail contrail = sparseContrail(random);
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
                }
            }
        }
    }

    private static ContrailEngineV2.Contrail sparseContrail(Random random) {
        ContrailEngineV2.Contrail contrail = contrail(new Color(
                random.nextInt(256), random.nextInt(256), random.nextInt(256),
                1 + random.nextInt(255)));
        float x = random.nextFloat() * 1000f;
        float y = random.nextFloat() * 1000f;
        float texCoord = 0f;
        int pointCount = 2 + random.nextInt(5);
        for (int i = 0; i < pointCount; i++) {
            ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
            x += 100f + random.nextFloat() * 900f;
            y += random.nextFloat() * 4f - 2f;
            point.point = new Vector2f(x, y);
            point.perp = new Vector2f(0f, 1f);
            point.vel = new Vector2f();
            point.width = 0.5f + random.nextFloat() * 3.5f;
            point.maxWidth = point.width;
            point.duration = 0.5f + random.nextFloat() * 3f;
            point.elapsed = random.nextFloat() * 4f;
            texCoord += random.nextFloat() * 2f;
            point.texCoord = texCoord;
            point.maxBrightness = random.nextFloat() * 1.5f;
            if (random.nextInt(4) == 0) {
                point.fadeOut = true;
                point.origMax = random.nextFloat() * 1.5f;
                point.elapsedWhenFadeOut = random.nextFloat() * 3f;
                point.lastProximityMult = random.nextFloat();
            }
            contrail.points.add(point);
        }
        return contrail;
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
        contrail.blendMode = com.fs.starfarer.loading.specs.EngineSlot.BlendMode.GLOW;
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

    private static ContrailEngineV2.Contrail randomContrail(Random random) {
        ContrailEngineV2.Contrail contrail = contrail(new Color(
                random.nextInt(256), random.nextInt(256), random.nextInt(256),
                1 + random.nextInt(255)));
        contrail.lastPoint = random.nextBoolean()
                ? null
                : new Vector2f(random.nextFloat() * 40f, random.nextFloat() * 40f);

        int pointCount = random.nextInt(7);
        float x = random.nextFloat() * 20f;
        float y = random.nextFloat() * 20f;
        float texCoord = 0f;
        for (int i = 0; i < pointCount; i++) {
            ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
            x += random.nextFloat() * 12f - 6f;
            y += random.nextFloat() * 12f - 6f;
            point.point = new Vector2f(x, y);
            float perpX = random.nextFloat() * 2f - 1f;
            float perpY = random.nextFloat() * 2f - 1f;
            point.perp = new Vector2f(perpX, perpY);
            point.vel = new Vector2f();
            point.width = random.nextFloat() * 30f;
            point.maxWidth = point.width;
            point.duration = switch (random.nextInt(6)) {
                case 0 -> 0f;
                case 1 -> 0.05f;
                default -> 0.5f + random.nextFloat() * 3f;
            };
            point.elapsed = random.nextFloat() * 4f;
            texCoord += random.nextFloat() * 0.5f;
            point.texCoord = texCoord;
            point.maxBrightness = random.nextFloat() * 1.5f;
            if (random.nextInt(4) == 0) {
                point.fadeOut = true;
                point.origMax = random.nextFloat() * 1.5f;
                point.elapsedWhenFadeOut = random.nextFloat() * 3f;
                point.lastProximityMult = random.nextFloat();
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
