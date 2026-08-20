package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.starfarer.combat.entities.ContrailEngine;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContrailAdvanceHelper} 与原版 advance 公式的位级等价验证。
 * <p>
 * 参考实现逐行转写自反编译原版 {@code ContrailEngine.advance(float)}（named
 * 版），覆盖单段更新公式（texU 推进/ended 加速老化/maxAge 钳制/progress 除法/
 * WIDEN·NARROW 宽度/vel 缩放与 position 推进）与组级编排（头段死亡移除循环、
 * ended 组移除、map 清理）。优化实现删除了仅写局部变量的死计算（f6/f7/f10）
 * 并对完全老化段走 progress 短路径，本测试对随机段状态断言全部字段
 * （含 Vector2f 分量）的位级一致，含 maxAge=0/负值、widthMode=null 等边界。
 */
class ContrailAdvanceHelperTest {

    // ------------------------------------------------------------------
    // 单段更新：位级等价
    // ------------------------------------------------------------------

    @Test
    void advanceSegmentMatchesReferenceBitwiseAcrossRandomStates() {
        Random random = new Random(0xDEAD_BEEFL);
        for (int trial = 0; trial < 4000; trial++) {
            FakeSegment reference = randomSegment(random);
            FakeSegment optimized = copyOf(reference);

            float amount = random.nextFloat() * 2f;
            boolean ended = random.nextBoolean();
            ContrailEngine.ContrailWidthMode widthMode = randomWidthMode(random);
            float widthMultiplier = random.nextFloat() * 3f;

            referenceAdvanceSegment(reference, amount, ended, widthMode, widthMultiplier);
            ContrailAdvanceHelper.advanceSegment(optimized, amount, ended, widthMode, widthMultiplier);

            assertSegmentBitsEqual(reference, optimized, "trial " + trial);
        }
    }

    /** 完全老化段（texU==maxAge）走 progress 短路径，输出与除法位级一致。 */
    @Test
    void fullyAgedSegmentUsesShortPathBitwiseEqualToDivision() {
        for (int i = 0; i < 500; i++) {
            FakeSegment reference = new FakeSegment();
            reference.maxAge = 1f + i * 0.37f;
            reference.u = reference.maxAge; // 已钳制的完全老化段
            reference.baseWidth = 4f;
            reference.position = new Vector2f(3f, -2f);
            reference.vel = new Vector2f(0.5f, -1.5f);
            FakeSegment optimized = copyOf(reference);

            referenceAdvanceSegment(reference, 0.016f, false, ContrailEngine.ContrailWidthMode.WIDEN, 2f);
            ContrailAdvanceHelper.advanceSegment(optimized, 0.016f, false,
                    ContrailEngine.ContrailWidthMode.WIDEN, 2f);

            assertSegmentBitsEqual(reference, optimized, "maxAge " + reference.maxAge);
            assertEquals(1.0f, Float.intBitsToFloat(Float.floatToRawIntBits(optimized.progress)),
                    "完全老化段 progress 恒为 1.0f");
        }
    }

    /** maxAge=0 时 0/0=NaN 语义必须保留（短路径守卫不得改写）。 */
    @Test
    void zeroMaxAgeKeepsNanProgressSemantics() {
        FakeSegment reference = new FakeSegment();
        reference.maxAge = 0f;
        reference.u = 0f;
        reference.baseWidth = 2f;
        reference.position = new Vector2f(0f, 0f);
        reference.vel = new Vector2f(1f, 1f);
        FakeSegment optimized = copyOf(reference);

        referenceAdvanceSegment(reference, 1f, false, ContrailEngine.ContrailWidthMode.NARROW, 1f);
        ContrailAdvanceHelper.advanceSegment(optimized, 1f, false,
                ContrailEngine.ContrailWidthMode.NARROW, 1f);

        assertSegmentBitsEqual(reference, optimized);
        assertTrue(Float.isNaN(optimized.progress), "maxAge=0 时 progress 保持 NaN");
        // 宽度公式在 NaN progress 下同样位级一致（NARROW 分支）
    }

    // ------------------------------------------------------------------
    // 组级编排：迭代序 + 死亡移除 + ended 组清理
    // ------------------------------------------------------------------

    @Test
    void advanceGroupMatchesReferenceAcrossRandomMaps() {
        Random random = new Random(0xCAFE_BEEFL);
        for (int trial = 0; trial < 400; trial++) {
            Map<Object, FakeGroup> reference = randomGroups(random);
            Map<Object, FakeGroup> optimized = copyOf(reference);
            float amount = random.nextFloat() * 1.5f;

            referenceAdvanceAll(reference, amount);
            ContrailAdvanceHelper.advance(optimized, amount);

            assertGroupsEqual(reference, optimized, "trial " + trial);
        }
    }

    /** ended 组段数 < 2 时整组移除（原版组级 else-if 分支）。 */
    @Test
    void endedGroupWithFewerThanTwoSegmentsIsRemoved() {
        Map<Object, FakeGroup> groups = new HashMap<>();
        FakeGroup ended = new FakeGroup();
        ended.key = "ended-trail";
        ended.ended = true;
        ended.widthMode = ContrailEngine.ContrailWidthMode.WIDEN;
        ended.widthMultiplier = 1f;
        groups.put(ended.key, ended);

        FakeGroup active = new FakeGroup();
        active.key = "active";
        active.widthMode = ContrailEngine.ContrailWidthMode.NARROW;
        active.segments.add(randomSegment(new Random(1)));
        groups.put(active.key, active);

        Map<Object, FakeGroup> reference = copyOf(groups);
        referenceAdvanceAll(reference, 1f);
        ContrailAdvanceHelper.advance(groups, 1f);

        assertGroupsEqual(reference, groups);
    }

    // ------------------------------------------------------------------
    // 参考实现：逐行转写反编译原版（独立于优化实现，防漂移）
    // ------------------------------------------------------------------

    /** 原版单段公式（含死计算外的全部可观察操作；f6/f7/f10 仅写局部变量，无从观察）。 */
    private static void referenceAdvanceSegment(FakeSegment s, float amount, boolean ended,
                                                ContrailEngine.ContrailWidthMode widthMode,
                                                float widthMultiplier) {
        s.u += amount;
        if (ended) {
            s.u += amount / 3.0f;
        }
        if (s.u > s.maxAge) {
            s.u = s.maxAge;
        }
        s.progress = s.u / s.maxAge;
        if (widthMode == ContrailEngine.ContrailWidthMode.WIDEN) {
            s.width = s.baseWidth * (s.progress * widthMultiplier + 1.0f);
        } else if (widthMode == ContrailEngine.ContrailWidthMode.NARROW) {
            s.width = s.baseWidth * (0.25f + (1.0f - s.progress) * 0.75f);
        }
        if (!ended) {
            s.vel.scale(1.0f - s.progress);
            s.position.x += s.vel.x * amount;
            s.position.y += s.vel.y * amount;
        }
    }

    /** 原版组级编排：逐段更新 → 头段死亡移除循环 → ended 组加入移除清单 → map 清理。 */
    private static void referenceAdvanceAll(Map<Object, FakeGroup> groups, float amount) {
        List<Object> removals = new ArrayList<>();
        for (FakeGroup group : groups.values()) {
            for (Object segmentObject : group.segments) {
                FakeSegment s = (FakeSegment) segmentObject;
                referenceAdvanceSegment(s, amount, group.ended, group.widthMode, group.widthMultiplier);
            }
            if (group.segments.size() >= 2) {
                while (group.segments.size() >= 2 && group.removeExpiredFromStore()) {
                    // 头段移除由 removeExpiredFromStore 完成
                }
            } else if (group.ended) {
                removals.add(group.key);
            }
        }
        for (Object key : removals) {
            groups.remove(key);
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static FakeSegment randomSegment(Random random) {
        FakeSegment s = new FakeSegment();
        s.position = new Vector2f(random.nextFloat() * 100f - 50f, random.nextFloat() * 100f - 50f);
        s.vel = new Vector2f(random.nextFloat() * 4f - 2f, random.nextFloat() * 4f - 2f);
        s.width = random.nextFloat() * 10f;
        s.baseWidth = random.nextFloat() * 10f;
        // maxAge 覆盖 0/负/正 边界 + 完全老化（u==maxAge）
        s.maxAge = switch (random.nextInt(5)) {
            case 0 -> 0f;
            case 1 -> -random.nextFloat();
            default -> random.nextFloat() * 5f;
        };
        s.u = random.nextBoolean() ? s.maxAge : random.nextFloat() * (s.maxAge + 1f);
        s.progress = random.nextFloat();
        s.alphaMult = random.nextFloat();
        return s;
    }

    private static ContrailEngine.ContrailWidthMode randomWidthMode(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> ContrailEngine.ContrailWidthMode.WIDEN;
            case 1 -> ContrailEngine.ContrailWidthMode.NARROW;
            default -> null; // 其他模式：宽度不变
        };
    }

    private static Map<Object, FakeGroup> randomGroups(Random random) {
        Map<Object, FakeGroup> groups = new HashMap<>();
        int groupCount = 1 + random.nextInt(4);
        for (int g = 0; g < groupCount; g++) {
            FakeGroup group = new FakeGroup();
            group.key = "key-" + g;
            group.ended = random.nextBoolean();
            group.widthMode = randomWidthMode(random);
            group.widthMultiplier = random.nextFloat() * 2f;
            int segmentCount = random.nextInt(6); // 0~5，覆盖 <2 分支
            for (int i = 0; i < segmentCount; i++) {
                FakeSegment s = randomSegment(random);
                s.maxAge = 0.5f + random.nextFloat() * 3f; // 死亡移除的时效区间
                group.segments.add(s);
            }
            groups.put(group.key, group);
        }
        return groups;
    }

    private static FakeSegment copyOf(FakeSegment src) {
        FakeSegment copy = new FakeSegment();
        copy.position = new Vector2f(src.position);
        copy.vel = new Vector2f(src.vel);
        copy.width = src.width;
        copy.baseWidth = src.baseWidth;
        copy.maxAge = src.maxAge;
        copy.progress = src.progress;
        copy.alphaMult = src.alphaMult;
        copy.u = src.u;
        copy.length = src.length;
        return copy;
    }

    private static Map<Object, FakeGroup> copyOf(Map<Object, FakeGroup> src) {
        Map<Object, FakeGroup> copy = new HashMap<>();
        for (Map.Entry<Object, FakeGroup> entry : src.entrySet()) {
            FakeGroup group = entry.getValue();
            FakeGroup groupCopy = new FakeGroup();
            groupCopy.key = group.key;
            groupCopy.ended = group.ended;
            groupCopy.widthMode = group.widthMode;
            groupCopy.widthMultiplier = group.widthMultiplier;
            for (Object segmentObject : group.segments) {
                groupCopy.segments.add(copyOf((FakeSegment) segmentObject));
            }
            copy.put(entry.getKey(), groupCopy);
        }
        return copy;
    }

    /** 测试夹具的 map（FakeGroup 值）直接传入 helper 的 Map<?,?> 入参。 */
    private static void assertSegmentBitsEqual(FakeSegment a, FakeSegment b, String message) {
        assertEquals(Float.floatToRawIntBits(a.u), Float.floatToRawIntBits(b.u), message + " texU");
        assertEquals(Float.floatToRawIntBits(a.progress), Float.floatToRawIntBits(b.progress), message + " progress");
        assertEquals(Float.floatToRawIntBits(a.width), Float.floatToRawIntBits(b.width), message + " width");
        assertEquals(Float.floatToRawIntBits(a.position.x), Float.floatToRawIntBits(b.position.x), message + " position.x");
        assertEquals(Float.floatToRawIntBits(a.position.y), Float.floatToRawIntBits(b.position.y), message + " position.y");
        assertEquals(Float.floatToRawIntBits(a.vel.x), Float.floatToRawIntBits(b.vel.x), message + " vel.x");
        assertEquals(Float.floatToRawIntBits(a.vel.y), Float.floatToRawIntBits(b.vel.y), message + " vel.y");
        assertEquals(Float.floatToRawIntBits(a.length), Float.floatToRawIntBits(b.length), message + " length");
        assertEquals(Float.floatToRawIntBits(a.alphaMult), Float.floatToRawIntBits(b.alphaMult), message + " alphaMult");
    }

    private static void assertSegmentBitsEqual(FakeSegment a, FakeSegment b) {
        assertSegmentBitsEqual(a, b, "");
    }

    private static void assertGroupsEqual(Map<Object, FakeGroup> a, Map<Object, FakeGroup> b, String message) {
        assertEquals(a.keySet(), b.keySet(), message + " 键集（ended 组移除/残留一致）");
        for (Object key : a.keySet()) {
            FakeGroup ga = a.get(key);
            FakeGroup gb = b.get(key);
            assertEquals(ga.segments.size(), gb.segments.size(), message + " 段数");
            for (int i = 0; i < ga.segments.size(); i++) {
                assertSegmentBitsEqual((FakeSegment) ga.segments.get(i), (FakeSegment) gb.segments.get(i),
                        message + " group " + key + " seg " + i + " ");
            }
        }
    }

    private static void assertGroupsEqual(Map<Object, FakeGroup> a, Map<Object, FakeGroup> b) {
        assertGroupsEqual(a, b, "");
    }

    private static final class FakeGroup implements ContrailGroupAccessor {
        final List<Object> segments = new ArrayList<>();
        boolean ended;
        ContrailEngine.ContrailWidthMode widthMode;
        float widthMultiplier;
        Object key;

        /** 夹具的原版 removeExpiredSegment 等价实现（含 length 簿记与 alphaMult 清零）。 */
        boolean removeExpiredFromStore() {
            FakeSegment s0 = (FakeSegment) segments.get(0);
            FakeSegment s1 = (FakeSegment) segments.get(1);
            if (s0.u >= s0.maxAge && s1.u >= s1.maxAge) {
                segments.remove(0);
                s1.length = 0;
                return true;
            }
            s0.alphaMult = 0;
            return false;
        }

        @Override
        public List<Object> ssoptimizer$getSegments() {
            return segments;
        }

        @Override
        public com.fs.graphics.TextureObject ssoptimizer$getTexture() {
            return null;
        }

        @Override
        public Vector2f ssoptimizer$getTail() {
            return null;
        }

        @Override
        public Color ssoptimizer$getColor() {
            return null;
        }

        @Override
        public EngineSlot.BlendMode ssoptimizer$getBlendMode() {
            return null;
        }

        @Override
        public boolean ssoptimizer$getEnded() {
            return ended;
        }

        @Override
        public ContrailEngine.ContrailWidthMode ssoptimizer$getWidthMode() {
            return widthMode;
        }

        @Override
        public float ssoptimizer$getWidthMultiplier() {
            return widthMultiplier;
        }

        @Override
        public float ssoptimizer$getSegmentDuration() {
            return 0f;
        }

        @Override
        public Object ssoptimizer$getKey() {
            return key;
        }

        @Override
        public boolean ssoptimizer$removeExpiredSegment() {
            return removeExpiredFromStore();
        }
    }

    private static final class FakeSegment implements ContrailSegmentAccessor {
        Vector2f position = new Vector2f();
        Vector2f vel = new Vector2f();
        float width;
        float baseWidth;
        float maxAge;
        float progress;
        float alphaMult;
        float u;
        float length;

        @Override
        public Vector2f ssoptimizer$getPosition() {
            return position;
        }

        @Override
        public Vector2f ssoptimizer$getNormal() {
            return null;
        }

        @Override
        public Vector2f ssoptimizer$getVel() {
            return vel;
        }

        @Override
        public float ssoptimizer$getWidth() {
            return width;
        }

        @Override
        public void ssoptimizer$setWidth(float width) {
            this.width = width;
        }

        @Override
        public float ssoptimizer$getBaseWidth() {
            return baseWidth;
        }

        @Override
        public float ssoptimizer$getMaxAge() {
            return maxAge;
        }

        @Override
        public float ssoptimizer$getProgress() {
            return progress;
        }

        @Override
        public void ssoptimizer$setProgress(float progress) {
            this.progress = progress;
        }

        @Override
        public float ssoptimizer$getAlphaMult() {
            return alphaMult;
        }

        @Override
        public float ssoptimizer$getU() {
            return u;
        }

        @Override
        public void ssoptimizer$setTexU(float texU) {
            this.u = texU;
        }
    }
}
