package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.combat.entities.ContrailEngine;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CampaignContrailAdvanceHelperAgedShortCircuitTest {

    @Test
    void fullyAgedSteadyGroupSkipsPointAdvanceButKeepsRemoval() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("aged", true, false, 4,
                ContrailEngine.ContrailWidthMode.WIDEN, 2f);
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.5f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.5f);

        assertContrailsEqual(reference, optimized);
        assertFalse(optimized.containsKey("aged"),
                "remove+全老化尾迹应从头段移除循环淘空后进移除清单");
    }

    @Test
    void fullyAgedSteadyNonRemoveGroupLeavesPointsUntouched() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("steady", false, false, 3,
                ContrailEngine.ContrailWidthMode.NARROW, 1.5f);
        optimized.put(contrail.source, contrail);

        List<ContrailEngineV2.ContrailPoint> before = new ArrayList<>();
        for (ContrailEngineV2.ContrailPoint point : contrail.points) {
            before.add(copyOf(point));
        }

        CampaignContrailAdvanceHelper.advance(optimized, 0.5f);

        assertTrue(optimized.containsKey("steady"), "无 remove/autoCleanup 不清理");
        assertEquals(2, contrail.points.size(), "3 点全老化 ⇒ 头点被移除一次后 < 3 停止");
        for (int i = 0; i < contrail.points.size(); i++) {
            ContrailEngineV2.ContrailPoint after = contrail.points.get(i);
            ContrailEngineV2.ContrailPoint orig = before.get(i + 1);
            assertEquals(Float.floatToRawIntBits(orig.elapsed), Float.floatToRawIntBits(after.elapsed),
                    "elapsed 不变（钳回原值）");
            assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(after.progress));
            assertEquals(Float.floatToRawIntBits(orig.width), Float.floatToRawIntBits(after.width),
                    "width 不变（已是稳态值）");
            assertEquals(Float.floatToRawIntBits(orig.point.x), Float.floatToRawIntBits(after.point.x));
            assertEquals(Float.floatToRawIntBits(orig.maxBrightness),
                    Float.floatToRawIntBits(after.maxBrightness));
        }
    }

    @Test
    void agedGroupWithMovingPointsFallsBackToPerPointAdvance() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("moving", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        contrail.points.get(0).vel = new Vector2f(3f, -2f);
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.25f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.25f);

        assertContrailsEqual(reference, optimized);
        ContrailEngineV2.ContrailPoint point = optimized.get("moving").points.get(0);
        assertEquals(0.75f, point.point.x, 1e-6f, "零速度守卫不满足 ⇒ position 照常推进");
    }

    @Test
    void agedGroupWithStaleProgressFallsBack() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("staleProgress", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        contrail.points.get(1).progress = 0.4f;
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.25f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.25f);

        assertContrailsEqual(reference, optimized);
        assertEquals(Float.floatToRawIntBits(1.0f),
                Float.floatToRawIntBits(optimized.get("staleProgress").points.get(1).progress),
                "progress 必须由逐点路径重写为 1.0f");
    }

    @Test
    void agedGroupWithStaleWidthFallsBack() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("staleWidth", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        contrail.widthMultiplier = 3f;
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.25f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.25f);

        assertContrailsEqual(reference, optimized);
        ContrailEngineV2.ContrailPoint point = optimized.get("staleWidth").points.get(0);
        assertEquals(Float.floatToRawIntBits(point.maxWidth * 4f),
                Float.floatToRawIntBits(point.width), "width 必须按新 widthMultiplier 重算");
    }

    @Test
    void agedGroupWithNonFadeOutPointFallsBack() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("notFaded", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        ContrailEngineV2.ContrailPoint active = contrail.points.get(1);
        active.fadeOut = false;
        active.maxBrightness = 0.8f;
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.25f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.25f);

        assertContrailsEqual(reference, optimized);
    }

    @Test
    void agedGroupWithZeroDurationFallsBack() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("zeroDuration", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        ContrailEngineV2.ContrailPoint zero = contrail.points.get(1);
        zero.duration = 0f;
        zero.elapsed = 0f;
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.25f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.25f);

        assertContrailsEqual(reference, optimized);
        assertTrue(Float.isNaN(optimized.get("zeroDuration").points.get(1).progress),
                "duration=0 必须保留 NaN progress 语义");
    }

    @Test
    void reusedRemovalsBufferLeavesNoResidueAcrossFrames() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail dying = steadyContrail("dying", true, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        optimized.put(dying.source, dying);
        ContrailEngineV2.Contrail survivor = steadyContrail("survivor", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        optimized.put(survivor.source, survivor);

        CampaignContrailAdvanceHelper.advance(optimized, 0.5f);
        assertFalse(optimized.containsKey("dying"));
        assertTrue(optimized.containsKey("survivor"));

        CampaignContrailAdvanceHelper.advance(optimized, 0.5f);
        assertTrue(optimized.containsKey("survivor"), "复用缓冲残留会误删存活尾迹");

        Map<Object, ContrailEngineV2.Contrail> reference = new HashMap<>();
        ContrailEngineV2.Contrail refSurvivor = steadyContrail("survivor", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        reference.put(refSurvivor.source, refSurvivor);
        referenceAdvanceAll(reference, 0.5f);
        referenceAdvanceAll(reference, 0.5f);
        assertContrailsEqual(reference, optimized);
    }

    @Test
    void advanceMatchesReferenceBitwiseAcrossRandomMaps() {
        Random random = new Random(0xB2_5CA7L);
        for (int trial = 0; trial < 400; trial++) {
            Map<Object, ContrailEngineV2.Contrail> reference = randomContrails(random);
            Map<Object, ContrailEngineV2.Contrail> optimized = copyOf(reference);
            float amount = random.nextFloat() * 1.5f;

            referenceAdvanceAll(reference, amount);
            CampaignContrailAdvanceHelper.advance(optimized, amount);

            assertContrailsEqual(reference, optimized, "trial " + trial);
        }
    }

    private static void referenceAdvancePoint(ContrailEngineV2.ContrailPoint p, float amount,
                                              boolean remove,
                                              ContrailEngine.ContrailWidthMode mode,
                                              float widthMultiplier) {
        p.elapsed += amount;
        if (remove) {
            p.elapsed += amount / 3.0f;
        }
        if (p.elapsed > p.duration) {
            p.elapsed = p.duration;
        }
        p.progress = p.elapsed / p.duration;
        if (p.fadeOut) {
            p.maxBrightness -= amount * 2.0f;
            if (p.maxBrightness < 0.0f) {
                p.maxBrightness = 0.0f;
            }
        }
        if (mode == ContrailEngine.ContrailWidthMode.WIDEN) {
            p.width = p.maxWidth * (p.progress * widthMultiplier + 1.0f);
        } else if (mode == ContrailEngine.ContrailWidthMode.NARROW) {
            p.width = p.maxWidth * (0.25f + (1.0f - p.progress) * 0.75f);
        }
        if (!remove) {
            p.point.x = p.point.x + p.vel.x * amount;
            p.point.y = p.point.y + p.vel.y * amount;
        }
    }

    private static void referenceAdvanceAll(Map<Object, ContrailEngineV2.Contrail> contrails, float amount) {
        List<Object> removals = new ArrayList<>();
        for (ContrailEngineV2.Contrail contrail : contrails.values()) {
            for (ContrailEngineV2.ContrailPoint point : contrail.points) {
                referenceAdvancePoint(point, amount, contrail.remove, contrail.mode,
                        contrail.widthMultiplier);
            }
            if (contrail.points.size() >= 3) {
                while (contrail.points.size() >= 3 && referenceRemoveFirstIfNecessary(contrail)) {
                }
            }
            if ((contrail.remove || contrail.autoCleanup) && contrail.points.size() < 3) {
                boolean allAged = true;
                for (ContrailEngineV2.ContrailPoint point : contrail.points) {
                    if (point.elapsed < point.duration) {
                        allAged = false;
                    }
                }
                if (allAged) {
                    removals.add(contrail.source);
                }
            }
        }
        for (Object key : removals) {
            contrails.remove(key);
        }
    }

    private static boolean referenceRemoveFirstIfNecessary(ContrailEngineV2.Contrail contrail) {
        ContrailEngineV2.ContrailPoint p0 = contrail.points.get(0);
        ContrailEngineV2.ContrailPoint p1 = contrail.points.get(1);
        ContrailEngineV2.ContrailPoint p2 = contrail.points.get(2);
        if (p0.elapsed >= p0.duration && p1.elapsed >= p1.duration && p2.elapsed >= p2.duration) {
            contrail.points.remove(0);
            contrail.totalLength = contrail.totalLength - p1.distToPrev;
            p1.distToPrev = 0.0f;
            return true;
        }
        p0.maxBrightness = 0.0f;
        return false;
    }

    private static ContrailEngineV2.Contrail steadyContrail(Object source, boolean remove,
                                                            boolean autoCleanup, int pointCount,
                                                            ContrailEngine.ContrailWidthMode mode,
                                                            float widthMultiplier) {
        ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        contrail.source = source;
        contrail.remove = remove;
        contrail.autoCleanup = autoCleanup;
        contrail.mode = mode;
        contrail.widthMultiplier = widthMultiplier;
        for (int i = 0; i < pointCount; i++) {
            ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
            point.point = new Vector2f(i * 10f, 0f);
            point.perp = new Vector2f(0f, 1f);
            point.vel = new Vector2f();
            point.duration = 1f;
            point.elapsed = 1f;
            point.progress = 1f;
            point.maxWidth = 2f;
            point.width = mode == ContrailEngine.ContrailWidthMode.WIDEN
                    ? 2f * (widthMultiplier + 1.0f)
                    : mode == ContrailEngine.ContrailWidthMode.NARROW ? 2f * 0.25f : 2f;
            point.fadeOut = true;
            point.maxBrightness = 0.0f;
            point.distToPrev = 10f;
            contrail.points.add(point);
            contrail.totalLength += 10f;
        }
        return contrail;
    }

    private static Map<Object, ContrailEngineV2.Contrail> randomContrails(Random random) {
        Map<Object, ContrailEngineV2.Contrail> contrails = new HashMap<>();
        int contrailCount = 1 + random.nextInt(4);
        for (int c = 0; c < contrailCount; c++) {
            boolean remove = random.nextBoolean();
            boolean autoCleanup = random.nextBoolean();
            ContrailEngine.ContrailWidthMode mode = switch (random.nextInt(3)) {
                case 0 -> ContrailEngine.ContrailWidthMode.WIDEN;
                case 1 -> ContrailEngine.ContrailWidthMode.NARROW;
                default -> ContrailEngine.ContrailWidthMode.CONSTANT;
            };
            float widthMultiplier = random.nextFloat() * 2f;
            int pointCount = random.nextInt(7);
            ContrailEngineV2.Contrail contrail = steadyContrail("key-" + c, remove, autoCleanup,
                    pointCount, mode, widthMultiplier);
            if (random.nextBoolean() && pointCount > 0) {
                ContrailEngineV2.ContrailPoint point =
                        contrail.points.get(random.nextInt(pointCount));
                switch (random.nextInt(6)) {
                    case 0 -> point.elapsed = random.nextFloat() * point.duration;
                    case 1 -> point.vel = new Vector2f(1f + random.nextFloat(), 0f);
                    case 2 -> point.progress = random.nextFloat();
                    case 3 -> point.width = 1f + random.nextFloat() * 5f;
                    case 4 -> {
                        point.fadeOut = false;
                        point.maxBrightness = random.nextFloat();
                    }
                    default -> {
                        point.duration = 0f;
                        point.elapsed = 0f;
                        point.progress = 0f;
                    }
                }
            }
            contrails.put(contrail.source, contrail);
        }
        return contrails;
    }

    private static ContrailEngineV2.ContrailPoint copyOf(ContrailEngineV2.ContrailPoint src) {
        ContrailEngineV2.ContrailPoint copy = new ContrailEngineV2.ContrailPoint();
        copy.point = new Vector2f(src.point);
        copy.perp = src.perp == null ? null : new Vector2f(src.perp);
        copy.vel = new Vector2f(src.vel);
        copy.width = src.width;
        copy.maxWidth = src.maxWidth;
        copy.duration = src.duration;
        copy.elapsed = src.elapsed;
        copy.progress = src.progress;
        copy.fadeOut = src.fadeOut;
        copy.maxBrightness = src.maxBrightness;
        copy.distToPrev = src.distToPrev;
        copy.texCoord = src.texCoord;
        copy.origMax = src.origMax;
        copy.lastProximityMult = src.lastProximityMult;
        copy.elapsedWhenFadeOut = src.elapsedWhenFadeOut;
        return copy;
    }

    private static Map<Object, ContrailEngineV2.Contrail> copyOf(
            Map<Object, ContrailEngineV2.Contrail> src) {
        Map<Object, ContrailEngineV2.Contrail> copy = new HashMap<>();
        for (Map.Entry<Object, ContrailEngineV2.Contrail> entry : src.entrySet()) {
            ContrailEngineV2.Contrail contrail = entry.getValue();
            ContrailEngineV2.Contrail contrailCopy = new ContrailEngineV2.Contrail();
            contrailCopy.source = contrail.source;
            contrailCopy.remove = contrail.remove;
            contrailCopy.autoCleanup = contrail.autoCleanup;
            contrailCopy.mode = contrail.mode;
            contrailCopy.widthMultiplier = contrail.widthMultiplier;
            contrailCopy.totalLength = contrail.totalLength;
            for (ContrailEngineV2.ContrailPoint point : contrail.points) {
                contrailCopy.points.add(copyOf(point));
            }
            copy.put(entry.getKey(), contrailCopy);
        }
        return copy;
    }

    private static void assertPointBitsEqual(ContrailEngineV2.ContrailPoint a,
                                             ContrailEngineV2.ContrailPoint b, String message) {
        assertEquals(Float.floatToRawIntBits(a.elapsed), Float.floatToRawIntBits(b.elapsed), message + " elapsed");
        assertEquals(Float.floatToRawIntBits(a.progress), Float.floatToRawIntBits(b.progress), message + " progress");
        assertEquals(Float.floatToRawIntBits(a.width), Float.floatToRawIntBits(b.width), message + " width");
        assertEquals(Float.floatToRawIntBits(a.maxBrightness), Float.floatToRawIntBits(b.maxBrightness),
                message + " maxBrightness");
        assertEquals(a.fadeOut, b.fadeOut, message + " fadeOut");
        assertEquals(Float.floatToRawIntBits(a.distToPrev), Float.floatToRawIntBits(b.distToPrev),
                message + " distToPrev");
        assertEquals(Float.floatToRawIntBits(a.point.x), Float.floatToRawIntBits(b.point.x), message + " point.x");
        assertEquals(Float.floatToRawIntBits(a.point.y), Float.floatToRawIntBits(b.point.y), message + " point.y");
    }

    private static void assertContrailsEqual(Map<Object, ContrailEngineV2.Contrail> a,
                                             Map<Object, ContrailEngineV2.Contrail> b, String message) {
        assertEquals(a.keySet(), b.keySet(), message + " 键集（尾迹清理一致）");
        for (Object key : a.keySet()) {
            ContrailEngineV2.Contrail ca = a.get(key);
            ContrailEngineV2.Contrail cb = b.get(key);
            assertEquals(ca.points.size(), cb.points.size(), message + " 点数");
            assertEquals(Float.floatToRawIntBits(ca.totalLength), Float.floatToRawIntBits(cb.totalLength),
                    message + " totalLength");
            for (int i = 0; i < ca.points.size(); i++) {
                assertPointBitsEqual(ca.points.get(i), cb.points.get(i),
                        message + " contrail " + key + " point " + i + " ");
            }
        }
    }

    private static void assertContrailsEqual(Map<Object, ContrailEngineV2.Contrail> a,
                                             Map<Object, ContrailEngineV2.Contrail> b) {
        assertContrailsEqual(a, b, "");
    }
}
