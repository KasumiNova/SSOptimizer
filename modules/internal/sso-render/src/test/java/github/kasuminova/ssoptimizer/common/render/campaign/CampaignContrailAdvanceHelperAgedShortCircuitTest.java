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

/**
 * {@link CampaignContrailAdvanceHelper} 的 B2 全老化短路与 removals 复用验证。
 * <p>
 * 短路条件（组内全部点满足，逐项保守）：elapsed==duration 且 duration!=0、
 * progress==1.0f、fadeOut 且 maxBrightness==+0.0f、width 已是稳态值、
 * 非 remove 组 vel 为零向量、amount>=0。验证面：
 * <ul>
 *   <li>全老化稳态组：跳过逐点推进后与逐点参考实现结果一致（移除行为、
 *       elapsed 钳制、map 清理）；</li>
 *   <li>条件逐项破坏（progress 未同步 / width 滞后 / vel 非零 / 未 fadeOut /
 *       duration==0 / amount<0 的逐帧语义不覆盖——负推进量不在原版语义内，
 *       仅以 amount>=0 守卫）：每组回退逐点路径，结果与参考实现位级一致；</li>
 *   <li>removals 复用：连续两次 advance 无跨帧残留（第二次调用不重复移除）。</li>
 * </ul>
 * 参考实现逐行转写自反编译原版 {@code ContrailEngineV2.advance(float)}
 * （named 仓 {@code ContrailEngineV2.java:197-258}），与优化实现独立防漂移。
 */
class CampaignContrailAdvanceHelperAgedShortCircuitTest {

    // ------------------------------------------------------------------
    // 全老化稳态组短路
    // ------------------------------------------------------------------

    /**
     * 全老化稳态组（remove=true、3 点）：跳过逐点推进后，removeFirstIfNecessary
     * 头段移除循环照常执行并淘空至 < 3 点，尾迹进移除清单——与逐点参考实现
     * 的 map 结果位级一致。
     */
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

    /** 非 remove 的全老化稳态组（零速度）：点状态逐字段不变，尾迹保留。 */
    @Test
    void fullyAgedSteadyNonRemoveGroupLeavesPointsUntouched() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("steady", false, false, 3,
                ContrailEngine.ContrailWidthMode.NARROW, 1.5f);
        optimized.put(contrail.source, contrail);

        // 快照推进前状态
        List<ContrailEngineV2.ContrailPoint> before = new ArrayList<>();
        for (ContrailEngineV2.ContrailPoint point : contrail.points) {
            before.add(copyOf(point));
        }

        CampaignContrailAdvanceHelper.advance(optimized, 0.5f);

        assertTrue(optimized.containsKey("steady"), "无 remove/autoCleanup 不清理");
        // 头段移除循环：前 3 点全老化 ⇒ removeFirstIfNecessary 移除头点（原版行为保留）
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

    // ------------------------------------------------------------------
    // 短路条件逐项破坏 ⇒ 回退逐点路径
    // ------------------------------------------------------------------

    /** vel 非零的完全老化点：position 必须照常推进（冻结即语义偏差），回退逐点路径。 */
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

    /** progress 未同步（外部写入/反序列化截断）：必须重写为 1.0f，回退逐点路径。 */
    @Test
    void agedGroupWithStaleProgressFallsBack() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("staleProgress", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        contrail.points.get(1).progress = 0.4f; // elapsed==duration 但 progress 未同步
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.25f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.25f);

        assertContrailsEqual(reference, optimized);
        assertEquals(Float.floatToRawIntBits(1.0f),
                Float.floatToRawIntBits(optimized.get("staleProgress").points.get(1).progress),
                "progress 必须由逐点路径重写为 1.0f");
    }

    /** width 滞后（updateContrail 改写 widthMultiplier 后）：必须重算，回退逐点路径。 */
    @Test
    void agedGroupWithStaleWidthFallsBack() {
        Map<Object, ContrailEngineV2.Contrail> optimized = new HashMap<>();
        ContrailEngineV2.Contrail contrail = steadyContrail("staleWidth", false, false, 2,
                ContrailEngine.ContrailWidthMode.WIDEN, 1f);
        contrail.widthMultiplier = 3f; // width 仍是 widthMultiplier=1 时的稳态值
        optimized.put(contrail.source, contrail);

        Map<Object, ContrailEngineV2.Contrail> reference = copyOf(optimized);
        referenceAdvanceAll(reference, 0.25f);
        CampaignContrailAdvanceHelper.advance(optimized, 0.25f);

        assertContrailsEqual(reference, optimized);
        ContrailEngineV2.ContrailPoint point = optimized.get("staleWidth").points.get(0);
        assertEquals(Float.floatToRawIntBits(point.maxWidth * 4f),
                Float.floatToRawIntBits(point.width), "width 必须按新 widthMultiplier 重算");
    }

    /** 未 fadeOut 的完全老化点（渲染侧仍有 fadeOut 检测价值）：回退逐点路径。 */
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

    /** duration==0（progress 0/0=NaN 语义）：回退逐点路径。 */
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

    // ------------------------------------------------------------------
    // removals 复用
    // ------------------------------------------------------------------

    /** 复用缓冲无跨帧残留：第一帧移除一批，第二帧不得重复/残留移除。 */
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

        // 第二帧：无新增可清理尾迹，survivor 不得因残留 key 被误删
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

    // ------------------------------------------------------------------
    // 随机状态位级等价（对照独立转写的原版参考实现）
    // ------------------------------------------------------------------

    /**
     * 随机尾迹 map（高比例全老化稳态组 + 逐项条件破坏组 + 活跃组）上，
     * {@link CampaignContrailAdvanceHelper#advance} 的 map 结果（键集、点数、
     * 逐点全部字段、totalLength 簿记、distToPrev 清零）与原版参考实现位级一致。
     */
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

    // ------------------------------------------------------------------
    // 参考实现：逐行转写反编译原版 advance(float)（独立于优化实现）
    // ------------------------------------------------------------------

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
                    // 头段移除由 referenceRemoveFirstIfNecessary 完成
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

    /** 原版 Contrail.removeFirstIfNecessary 的独立转写（helper 侧调用真实游戏方法）。 */
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

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 全老化稳态组：全部点 elapsed==duration、progress==1、fadeOut 且
     * maxBrightness==+0.0f、width 已是稳态值、vel 零向量（短路目标形态）。
     */
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

    /**
     * 随机尾迹 map：约半数组为全老化稳态（随机 remove/autoCleanup/mode），其余
     * 逐项破坏短路条件（活跃点、vel 非零、progress 未同步、width 滞后、未
     * fadeOut、duration==0），覆盖短路命中与回退两路径及组级移除编排。
     */
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
                // 破坏一项短路条件，回退逐点路径
                ContrailEngineV2.ContrailPoint point =
                        contrail.points.get(random.nextInt(pointCount));
                switch (random.nextInt(6)) {
                    case 0 -> point.elapsed = random.nextFloat() * point.duration; // 未老化
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
