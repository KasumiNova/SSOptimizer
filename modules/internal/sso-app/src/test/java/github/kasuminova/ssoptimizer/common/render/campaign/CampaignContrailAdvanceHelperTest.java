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

class CampaignContrailAdvanceHelperTest {

    @Test
    void advancePointMatchesReferenceBitwiseAcrossRandomStates() {
        Random random = new Random(0xDEAD_BEEFL);
        for (int trial = 0; trial < 4000; trial++) {
            ContrailEngineV2.ContrailPoint reference = randomPoint(random);
            ContrailEngineV2.ContrailPoint optimized = copyOf(reference);

            float amount = random.nextFloat() * 2f;
            boolean remove = random.nextBoolean();
            ContrailEngine.ContrailWidthMode mode = randomWidthMode(random);
            float widthMultiplier = random.nextFloat() * 3f;

            referenceAdvancePoint(reference, amount, remove, mode, widthMultiplier);
            CampaignContrailAdvanceHelper.advancePoint(optimized, amount,
                    amount / 3.0f, amount * 2.0f, remove, mode, widthMultiplier);

            assertPointBitsEqual(reference, optimized, "trial " + trial);
        }
    }

    @Test
    void fullyAgedPointUsesShortPathBitwiseEqualToDivision() {
        for (int i = 0; i < 500; i++) {
            ContrailEngineV2.ContrailPoint reference = new ContrailEngineV2.ContrailPoint();
            reference.duration = 1f + i * 0.37f;
            reference.elapsed = reference.duration;
            reference.maxWidth = 4f;
            reference.width = 4f;
            reference.point = new Vector2f(3f, -2f);
            reference.vel = new Vector2f(0.5f, -1.5f);
            ContrailEngineV2.ContrailPoint optimized = copyOf(reference);

            float amount = 0.016f;
            referenceAdvancePoint(reference, amount, false,
                    ContrailEngine.ContrailWidthMode.WIDEN, 2f);
            CampaignContrailAdvanceHelper.advancePoint(optimized, amount,
                    amount / 3.0f, amount * 2.0f, false,
                    ContrailEngine.ContrailWidthMode.WIDEN, 2f);

            assertPointBitsEqual(reference, optimized, "duration " + reference.duration);
            assertEquals(1.0f, optimized.progress, "完全老化点 progress 恒为 1.0f");
        }
    }

    @Test
    void zeroDurationKeepsNanProgressSemantics() {
        ContrailEngineV2.ContrailPoint reference = new ContrailEngineV2.ContrailPoint();
        reference.duration = 0f;
        reference.elapsed = 0f;
        reference.maxWidth = 2f;
        reference.point = new Vector2f(0f, 0f);
        reference.vel = new Vector2f(1f, 1f);
        ContrailEngineV2.ContrailPoint optimized = copyOf(reference);

        float amount = 1f;
        referenceAdvancePoint(reference, amount, false,
                ContrailEngine.ContrailWidthMode.NARROW, 1f);
        CampaignContrailAdvanceHelper.advancePoint(optimized, amount,
                amount / 3.0f, amount * 2.0f, false,
                ContrailEngine.ContrailWidthMode.NARROW, 1f);

        assertPointBitsEqual(reference, optimized);
        assertTrue(Float.isNaN(optimized.progress), "duration=0 时 progress 保持 NaN");
    }

    @Test
    void fadeOutPointBrightnessDecaysAndClampsAtZero() {
        ContrailEngineV2.ContrailPoint reference = new ContrailEngineV2.ContrailPoint();
        reference.duration = 3f;
        reference.elapsed = 1f;
        reference.fadeOut = true;
        reference.maxBrightness = 0.05f;
        reference.maxWidth = 2f;
        reference.point = new Vector2f(0f, 0f);
        reference.vel = new Vector2f(1f, 1f);
        ContrailEngineV2.ContrailPoint optimized = copyOf(reference);

        float amount = 0.5f;
        referenceAdvancePoint(reference, amount, false,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        CampaignContrailAdvanceHelper.advancePoint(optimized, amount,
                amount / 3.0f, amount * 2.0f, false,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);

        assertPointBitsEqual(reference, optimized);
        assertEquals(0.0f, optimized.maxBrightness, "衰减越界后钳 0");
    }

    @Test
    void advanceContrailsMatchesReferenceAcrossRandomMaps() {
        Random random = new Random(0xCAFE_BEEFL);
        for (int trial = 0; trial < 400; trial++) {
            Map<Object, ContrailEngineV2.Contrail> reference = randomContrails(random);
            Map<Object, ContrailEngineV2.Contrail> optimized = copyOf(reference);
            float amount = random.nextFloat() * 1.5f;

            referenceAdvanceAll(reference, amount);
            CampaignContrailAdvanceHelper.advance(optimized, amount);

            assertContrailsEqual(reference, optimized, "trial " + trial);
        }
    }

    @Test
    void removedContrailWithAllAgedPointsIsDroppedFromMap() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail removed = contrail("removed", true, false, 2);
        for (ContrailEngineV2.ContrailPoint point : removed.points) {
            point.elapsed = point.duration;
        }
        optimized.put(removed.source, removed);

        ContrailEngineV2.Contrail active = contrail("active", false, false, 2);
        optimized.put(active.source, active);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.5f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.5f);

        assertContrailsEqual(reference, optimized);
        assertFalse(optimized.containsKey("removed"), "remove+全老化尾迹应被移除");
        assertTrue(optimized.containsKey("active"));
    }

    @Test
    void autoCleanupContrailWithAllAgedPointsIsDroppedFromMap() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail cleanup = contrail("cleanup", false, true, 1);
        for (ContrailEngineV2.ContrailPoint point : cleanup.points) {
            point.elapsed = point.duration;
        }
        optimized.put(cleanup.source, cleanup);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.5f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.5f);

        assertContrailsEqual(reference, optimized);
        assertTrue(optimized.isEmpty(), "autoCleanup+全老化尾迹应被移除");
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

    private static ContrailEngineV2.Contrail contrail(Object source, boolean remove,
                                                      boolean autoCleanup, int pointCount) {
        ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        contrail.source = source;
        contrail.remove = remove;
        contrail.autoCleanup = autoCleanup;
        contrail.mode = ContrailEngine.ContrailWidthMode.WIDEN;
        contrail.widthMultiplier = 1f;
        for (int i = 0; i < pointCount; i++) {
            ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
            point.point = new Vector2f(i * 10f, 0f);
            point.perp = new Vector2f(0f, 1f);
            point.vel = new Vector2f(0.5f, 0.5f);
            point.duration = 1f;
            point.maxWidth = 2f;
            point.width = 2f;
            contrail.points.add(point);
        }
        return contrail;
    }

    private static ContrailEngineV2.ContrailPoint randomPoint(Random random) {
        ContrailEngineV2.ContrailPoint point = new ContrailEngineV2.ContrailPoint();
        point.point = new Vector2f(random.nextFloat() * 100f - 50f, random.nextFloat() * 100f - 50f);
        point.vel = new Vector2f(random.nextFloat() * 4f - 2f, random.nextFloat() * 4f - 2f);
        point.width = random.nextFloat() * 10f;
        point.maxWidth = random.nextFloat() * 10f;
        point.duration = switch (random.nextInt(5)) {
            case 0 -> 0f;
            case 1 -> 0.01f;
            default -> random.nextFloat() * 5f;
        };
        point.elapsed = random.nextBoolean()
                ? point.duration
                : random.nextFloat() * (point.duration + 1f);
        point.fadeOut = random.nextBoolean();
        point.maxBrightness = random.nextFloat() * 2f;
        point.distToPrev = random.nextFloat() * 50f;
        return point;
    }

    private static ContrailEngine.ContrailWidthMode randomWidthMode(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> ContrailEngine.ContrailWidthMode.WIDEN;
            case 1 -> ContrailEngine.ContrailWidthMode.NARROW;
            default -> null;
        };
    }

    private static Map<Object, ContrailEngineV2.Contrail> randomContrails(Random random) {
        Map<Object, ContrailEngineV2.Contrail> contrails = new HashMap<>();
        int contrailCount = 1 + random.nextInt(4);
        for (int c = 0; c < contrailCount; c++) {
            ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
            contrail.source = "key-" + c;
            contrail.remove = random.nextBoolean();
            contrail.autoCleanup = random.nextBoolean();
            contrail.mode = randomWidthMode(random);
            contrail.widthMultiplier = random.nextFloat() * 2f;
            int pointCount = random.nextInt(7);
            for (int i = 0; i < pointCount; i++) {
                ContrailEngineV2.ContrailPoint point = randomPoint(random);
                point.duration = 0.5f + random.nextFloat() * 3f;
                contrail.points.add(point);
                contrail.totalLength += point.distToPrev;
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
        assertEquals(Float.floatToRawIntBits(a.distToPrev), Float.floatToRawIntBits(b.distToPrev),
                message + " distToPrev");
        assertEquals(Float.floatToRawIntBits(a.point.x), Float.floatToRawIntBits(b.point.x), message + " point.x");
        assertEquals(Float.floatToRawIntBits(a.point.y), Float.floatToRawIntBits(b.point.y), message + " point.y");
    }

    private static void assertPointBitsEqual(ContrailEngineV2.ContrailPoint a,
                                             ContrailEngineV2.ContrailPoint b) {
        assertPointBitsEqual(a, b, "");
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
