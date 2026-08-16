package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CollectedBatch;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CoreInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.FrameInput;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.GlowInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.SlotInput;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.Stage;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.StripInstance;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 引擎实例收集器测试：参考实现逐行转写自反编译原版
 * {@code Engine.render(float)} / {@code renderFighter(float)}，与收集器输出逐位对比。
 */
class EngineInstanceCollectorTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 常规舰船帧参数（非 omega、withSpread、非战机/导弹、无 shifter）。 */
    private static FrameInput shipFrame() {
        return new FrameInput(
                1.0f, 5.0f,
                false, true, false, false, false,
                0.7f, 0.5f,
                false, 0.0f, 0.0f, 0.0f,
                11, 22, 33, 44, 1.0f, 1.0f);
    }

    private static SlotInput slot(boolean primaryGlowType) {
        return new SlotInput(
                0.5f, 0.5f,
                0.3f, 0.2f, 10.0f,
                3.0f, 4.0f, 45.0f,
                20.0f, 30.0f, 10.0f,
                new Color(255, 128, 64, 255), null,
                1.2f, primaryGlowType);
    }

    private static void assertFloatBits(float expected, float actual, String field) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual),
                field + " 应与原版公式逐位一致（expected=" + expected + ", actual=" + actual + "）");
    }

    // ------------------------------------------------------------------
    // 参考实现：逐行转写原版公式（与收集器相互独立，防漂移）
    // ------------------------------------------------------------------

    private static final class VanillaRef {
        final float var5;
        final float var53;

        VanillaRef(FrameInput frame) {
            float v5 = -(frame.angularVelocity() * 0.15f);
            if (frame.omegaMode()) {
                float var6 = Math.signum(-frame.angularVelocity());
                float var7 = Math.min(1.0f, Math.abs(frame.angularVelocity()) / 120.0f);
                var7 *= var7;
                v5 = var6 * var7 * 10.0f;
            }
            this.var5 = v5;

            float v53 = frame.secondaryBrightness();
            if (v53 > 0.0f) {
                v53 = (float) Math.sqrt(v53);
            }
            this.var53 = v53 * 0.75f;
        }

        /** 原版 ship 槽位公共参数（var16/var22/var24 等）。 */
        float[] slotParams(FrameInput frame, SlotInput slot) {
            float var9 = slot.flameLevel();
            float var10 = slot.adjustedLevel();
            float var13 = slot.spread();
            float var14 = slot.maxSpread();
            float var16 = var9 / 0.4f;
            if (var16 > 1.0f) {
                var16 = 1.0f;
            }
            float var45;
            float var47;
            if (frame.boostedFlameMode()) {
                var45 = Math.min(var10, 0.8f) / 0.8f;
                var45 *= var45;
                if (var45 < 0.45000005f) {
                    var45 = 0.45000005f;
                }
                var47 = Math.max(0.0f, var10 - 0.8f) / 0.19999999f;
                var47 *= var47;
            } else {
                var45 = Math.max(0.09f, var10 - 0.8f) / 0.19999999f;
                var47 = Math.max(0.0f, var10 - 0.8f) / 0.19999999f;
            }
            if (frame.omegaMode()) {
                var45 *= (var13 * 2.0f + var14) / var14;
            } else {
                var45 *= (var13 + var14) / var14;
            }
            float var19 = frame.primaryBrightness();
            float var20 = slot.slotLength() + slot.slotLength() * 0.25f * var19;
            var20 += slot.slotLength() * frame.lengthShiftCurr();
            float var21 = slot.slotWidth();
            var21 += slot.slotWidth() * frame.widthShiftCurr();
            float var22 = var20 * (0.2f + var47 * 0.8f);
            float var23 = slot.slotWidth() * (0.1f + var45 * 0.9f);
            float var24 = var21 * (0.1f + var45 * 0.9f);
            if (!frame.withSpread()) {
                float var25 = var13 / 90.0f;
                if (var25 > 1.0f) {
                    var25 = 1.0f;
                }
                if (var25 < 0.0f) {
                    var25 = 0.0f;
                }
                var13 = 0.0f;
                var22 *= 1.0f - var25 * 0.5f;
                var24 *= 1.0f + var25 * 0.25f;
            }
            float var50 = (1.0f - var22 / var20) * var13;
            float var27 = 6.0f;
            if (frame.omegaMode()) {
                var27 = 1.0f;
                var50 = 0.0f;
            }
            float var29 = Math.min(var23 / 2.0f, var22 / 4.0f);
            float var30 = var29 / var22;
            // 返回：edgeAlpha, stripLength, innerWidth, stripWidth, spreadRotation, passCount,
            //       innerLength, texSpan, spread(重置后), maxSpread, primaryBrightness
            return new float[]{var16, var22, var23, var24, var50, var27, var29, var30, var13, var14, var19};
        }

        StripInstance stripPass(FrameInput frame, SlotInput slot, float[] p, float texU, int pass) {
            float var16 = p[0];
            float var22 = p[1];
            float var24 = p[3];
            float var50 = p[4];
            float var27 = p[5];
            float var29 = p[6];
            float var30 = p[7];

            float var33 = pass;
            float var34 = (var27 - var33 - 1.0f) / var27 * var5;
            if (frame.omegaMode()) {
                var34 = var5;
            }
            float var35 = 1.0f;
            if (var33 % 2.0f == 0.0f) {
                var35 = -1.0f;
            }
            float var36 = (var33 + 1.0f) / 2.0f;
            float rot2 = (var27 / 2.0f - var36 - 1.0f) / (var27 / 2.0f) * var35 * 2.0f * var50;
            float tx = (var27 - var33 - 1.0f) * var29 / (var27 * 2.0f);
            float sx = 0.5f + 0.5f * (var33 + 1.0f) / var27;
            float sy = 1.0f * (var27 - var33) / var27;

            Color c = slot.color();
            int alphaStart = ((byte) (int) (var33 * 5.0f * c.getAlpha() / 255.0f
                    * frame.alphaScale() * var16)) & 0xFF;
            int alphaMid = ((byte) (int) (100.0f * 1.0f * c.getAlpha() / 255.0f
                    * frame.alphaScale() * var16)) & 0xFF;

            int texId = slot.primaryGlowType()
                    ? frame.stripPrimaryTextureId() : frame.stripSecondaryTextureId();
            return new StripInstance(
                    slot.posX(), slot.posY(), slot.midArcAngle(),
                    var34, rot2, tx, sx, sy,
                    var24 * 0.5f, var29, var22,
                    texU, var30, var9(slot),
                    c.getRed(), c.getGreen(), c.getBlue(),
                    alphaStart, alphaMid, texId);
        }

        float var9(SlotInput slot) {
            return slot.flameLevel() * 1.0f;
        }
    }

    private static void assertStripEquals(StripInstance expected, StripInstance actual) {
        assertFloatBits(expected.posX(), actual.posX(), "posX");
        assertFloatBits(expected.posY(), actual.posY(), "posY");
        assertFloatBits(expected.angle(), actual.angle(), "angle");
        assertFloatBits(expected.rotation1(), actual.rotation1(), "rotation1");
        assertFloatBits(expected.rotation2(), actual.rotation2(), "rotation2");
        assertFloatBits(expected.translateX(), actual.translateX(), "translateX");
        assertFloatBits(expected.scaleX(), actual.scaleX(), "scaleX");
        assertFloatBits(expected.scaleY(), actual.scaleY(), "scaleY");
        assertFloatBits(expected.halfWidth(), actual.halfWidth(), "halfWidth");
        assertFloatBits(expected.innerLength(), actual.innerLength(), "innerLength");
        assertFloatBits(expected.stripLength(), actual.stripLength(), "stripLength");
        assertFloatBits(expected.texU(), actual.texU(), "texU");
        assertFloatBits(expected.texSpan(), actual.texSpan(), "texSpan");
        assertFloatBits(expected.texAdvance(), actual.texAdvance(), "texAdvance");
        assertEquals(expected.red(), actual.red(), "red");
        assertEquals(expected.green(), actual.green(), "green");
        assertEquals(expected.blue(), actual.blue(), "blue");
        assertEquals(expected.alphaStart(), actual.alphaStart(), "alphaStart");
        assertEquals(expected.alphaMid(), actual.alphaMid(), "alphaMid");
        assertEquals(expected.textureId(), actual.textureId(), "textureId");
    }

    // ------------------------------------------------------------------
    // 条带实例：舰船 6 pass 逐位一致
    // ------------------------------------------------------------------

    @Test
    void shipStripInstancesMatchVanillaFormulaBitExact() {
        FrameInput frame = shipFrame();
        SlotInput slot = slot(true);
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, List.of(slot), batch);

        assertEquals(1, batch.strips.size());
        List<StripInstance> instances = batch.strips.get(0).instances();
        assertEquals(6, instances.size(), "非 omega 舰船每槽 6 个条带 pass");

        VanillaRef ref = new VanillaRef(frame);
        float[] p = ref.slotParams(frame, slot);
        float texU = slot.texU();
        for (int pass = 0; pass < 6; pass++) {
            assertStripEquals(ref.stripPass(frame, slot, p, texU, pass), instances.get(pass));
            texU += 1.0f / 6.0f;
        }
    }

    @Test
    void omegaSlotEmitsTwoLayersOfSinglePass() {
        FrameInput frame = new FrameInput(
                1.0f, 5.0f,
                true, true, false, false, false,
                0.7f, 0.5f,
                false, 0.0f, 0.0f, 0.0f,
                11, 22, 33, 44, 1.0f, 1.0f);
        SlotInput slot = slot(true);
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, List.of(slot), batch);

        List<StripInstance> instances = batch.strips.get(0).instances();
        assertEquals(2, instances.size(), "omega 模式为 1 pass × 2 layer");

        VanillaRef ref = new VanillaRef(frame);
        float[] p = ref.slotParams(frame, slot);
        float texU = slot.texU();
        for (int layer = 0; layer < 2; layer++) {
            assertStripEquals(ref.stripPass(frame, slot, p, texU, 0), instances.get(layer));
            texU += 1.0f;
        }
        // omega：rotation1 为 omega 角速度公式、rotation2 恒 0
        float expectedRot1 = Math.signum(-5.0f)
                * (float) Math.pow(Math.min(1.0f, 5.0f / 120.0f), 2) * 10.0f;
        assertEquals(0.0f, instances.get(0).rotation2());
        assertTrue(Math.abs(instances.get(0).rotation1() - expectedRot1) < 1e-6);
    }

    @Test
    void withoutSpreadSlotResetsSpreadAndAdjustsStrip() {
        FrameInput frame = new FrameInput(
                1.0f, 5.0f,
                false, false, false, false, false,
                0.7f, 0.5f,
                false, 0.0f, 0.0f, 0.0f,
                11, 22, 33, 44, 1.0f, 1.0f);
        SlotInput slot = slot(true);
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, List.of(slot), batch);

        VanillaRef ref = new VanillaRef(frame);
        float[] p = ref.slotParams(frame, slot);
        assertEquals(0.0f, p[8], "withSpread=false 时 spread 应被重置为 0（参考实现自检）");
        float texU = slot.texU();
        for (int pass = 0; pass < 6; pass++) {
            assertStripEquals(ref.stripPass(frame, slot, p, texU, pass),
                    batch.strips.get(0).instances().get(pass));
            texU += 1.0f / 6.0f;
        }
    }

    // ------------------------------------------------------------------
    // 核心 / 辉光
    // ------------------------------------------------------------------

    @Test
    void shipCoreInstanceMatchesVanillaFormula() {
        FrameInput frame = shipFrame();
        SlotInput slot = slot(true);
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, List.of(slot), batch);

        assertEquals(1, batch.cores.size());
        List<CoreInstance> cores = batch.cores.get(0).instances();
        assertEquals(1, cores.size());
        CoreInstance core = cores.get(0);

        VanillaRef ref = new VanillaRef(frame);
        float[] p = ref.slotParams(frame, slot);
        float var16 = p[0];
        int expectedAlpha = ((byte) (int) (slot.flameLevel() * 50.0f * 255 / 255.0f
                * frame.alphaScale() * var16)) & 0xFF;
        assertEquals(expectedAlpha, core.alpha());
        assertEquals(25, core.alpha(), "0.5*50*1*1 = 25");
        assertFloatBits(p[1], core.stripLength(), "stripLength");
        assertFloatBits(p[3] * 0.5f, core.halfWidth(), "halfWidth");
        assertFloatBits(slot.coreRotation(), core.coreRotation(), "coreRotation");
        assertFloatBits(0.0f, core.omegaRotation(), "非 omega 核心无附加旋转");
        assertEquals(33, core.textureId());
    }

    @Test
    void glowInstancesMatchVanillaSizeAndAlpha() {
        FrameInput frame = shipFrame();
        SlotInput slot = slot(true);
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, List.of(slot), batch);

        assertEquals(1, batch.glows.size());
        List<GlowInstance> glows = batch.glows.get(0).instances();
        assertEquals(2, glows.size(), "每槽 glow 为外圈 + 白芯两个 quad");

        // 参考：var57 = var24*(2+var19)；var61 = min(var16, max(max(var19*0.25, spread/maxSpread*0.5), var53, 0.6)*0.75*f)
        VanillaRef ref = new VanillaRef(frame);
        float[] p = ref.slotParams(frame, slot);
        float var24 = p[3];
        float var19 = p[10];
        float var57 = var24 * (2.0f + 1.0f * var19);
        float var59 = Math.max(var19 * 0.25f, slot.spread() / slot.maxSpread() * 0.5f);
        var59 = Math.max(var59, ref.var53);
        float var61 = Math.max(var59, 1.0f - 0.4f) * 0.75f * frame.alphaScale();
        var61 = Math.min(p[0], var61);
        if (var61 < 0.5f) {
            var57 *= 0.15f + 0.85f * (var61 / 0.5f);
        }
        float var39 = Math.min(var57, 15.0f) * 1.0f * var19;
        var39 *= 1.0f + ref.var53;

        GlowInstance outer = glows.get(0);
        assertFloatBits(slot.glowSizeMult() * (var57 * 2.0f + var39), outer.size(), "outerSize");
        assertEquals(((byte) (255 * var61)) & 0xFF, outer.alpha());
        assertEquals(255, outer.red());
        assertEquals(128, outer.green());
        assertEquals(64, outer.blue());
        assertFloatBits(0.9f, outer.scaleX(), "舰船 glow 处于 S(0.9,1) 矩阵内");
        assertFloatBits(slot.coreRotation(), outer.coreRotation(), "舰船 glow 带核心旋转");
        assertEquals(44, outer.textureId());

        GlowInstance inner = glows.get(1);
        assertFloatBits(slot.glowSizeMult() * var57 * 0.75f, inner.size(), "innerSize");
        assertEquals(((byte) (255 * var61)) & 0xFF, inner.alpha());
        assertEquals(255, inner.red());
        assertEquals(255, inner.green());
        assertEquals(255, inner.blue());
    }

    @Test
    void glowSkippedWhenMaxSpreadZeroByNaNPropagation() {
        SlotInput noSpread = new SlotInput(
                0.5f, 0.5f,
                0.3f, 0.2f, 0.0f,
                3.0f, 4.0f, 45.0f,
                0.0f, 30.0f, 10.0f,
                new Color(255, 128, 64, 255), null,
                1.2f, true);
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(shipFrame(), List.of(noSpread), batch);

        assertTrue(batch.glows.isEmpty(),
                "maxSpread==0 时 spread/maxSpread 为 NaN，原版 glow 两个分支均不命中");
        assertFalse(batch.strips.isEmpty(), "条带实例仍发射（NaN 顶点由 GPU 丢弃，与原版一致）");
        assertTrue(Float.isNaN(batch.strips.get(0).instances().get(0).halfWidth()),
                "原版 maxSpread==0 时条带宽度为 NaN");
    }

    // ------------------------------------------------------------------
    // 战机路径
    // ------------------------------------------------------------------

    @Test
    void fighterSlotEmitsThreePassesAndNoCore() {
        FrameInput frame = new FrameInput(
                1.0f, 5.0f,
                false, true, true, false, false,
                0.7f, 0.5f,
                false, 0.0f, 0.0f, 0.0f,
                11, 22, 33, 44, 1.0f, 1.0f);
        SlotInput slot = slot(false);
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, List.of(slot), batch);

        assertEquals(3, batch.strips.get(0).instances().size(), "战机每槽 3 个条带 pass");
        assertTrue(batch.cores.isEmpty(), "战机路径无火焰核心 pass");

        // 参考：战机公式（passCount=3、midAlpha 系数 255、无 spread 旋转）
        float var7 = 0.5f;
        float var14 = Math.min(var7 / 0.4f, 1.0f);
        float var35 = Math.max(0.09f, var7 - 0.8f) / 0.19999999f;
        var35 *= (10.0f + 20.0f) / 20.0f;
        float var18 = 30.0f + 30.0f * 0.25f * 0.7f;
        float var19w = 10.0f;
        float var20 = var18 * 0.2f;
        float var22 = var19w * (0.1f + var35 * 0.9f);
        float var26 = Math.min(var22 / 2.0f, var20 / 4.0f);
        float var27t = var26 / var20;
        float angular = -(5.0f * 0.15f);

        float texU = slot.texU();
        for (int pass = 0; pass < 3; pass++) {
            StripInstance actual = batch.strips.get(0).instances().get(pass);
            float passF = pass;
            assertFloatBits((3.0f - passF - 1.0f) / 3.0f * angular, actual.rotation1(), "rotation1");
            assertFloatBits(0.0f, actual.rotation2(), "战机无 spread 旋转");
            assertFloatBits((3.0f - passF - 1.0f) * var26 / 6.0f, actual.translateX(), "translateX");
            assertFloatBits(0.5f + 0.5f * (passF + 1.0f) / 3.0f, actual.scaleX(), "scaleX");
            assertFloatBits(1.0f * (3.0f - passF) / 3.0f, actual.scaleY(), "scaleY");
            assertFloatBits(var22 * 0.5f, actual.halfWidth(), "halfWidth");
            assertFloatBits(var26, actual.innerLength(), "innerLength");
            assertFloatBits(var20, actual.stripLength(), "stripLength");
            assertFloatBits(texU, actual.texU(), "texU");
            assertFloatBits(var27t, actual.texSpan(), "texSpan");
            assertEquals(((byte) (int) (passF * 5.0f * 255 / 255.0f * 1.0f * var14)) & 0xFF,
                    actual.alphaStart(), "alphaStart");
            assertEquals(((byte) (int) (255.0f * 255 / 255.0f * 1.0f * var14)) & 0xFF,
                    actual.alphaMid(), "战机 midAlpha 系数为 255");
            assertEquals(22, actual.textureId(), "secondary glowType 使用 secondary 纹理");
            texU += 1.0f / 3.0f;
        }

        // 战机 glow：无核心旋转与 0.9 缩放
        assertEquals(2, batch.glows.get(0).instances().size());
        GlowInstance glow = batch.glows.get(0).instances().get(0);
        assertFloatBits(1.0f, glow.scaleX(), "战机 glow 无 S(0.9,1)");
        assertFloatBits(0.0f, glow.coreRotation(), "战机 glow 无核心旋转");
    }

    // ------------------------------------------------------------------
    // 分组键与顺序
    // ------------------------------------------------------------------

    @Test
    void groupingByStageAndTexturePreservesFirstAppearanceOrder() {
        FrameInput frame = shipFrame();
        List<SlotInput> slots = new ArrayList<>();
        slots.add(slot(true));   // primary → 11
        slots.add(slot(false));  // secondary → 22
        slots.add(slot(true));   // primary → 11
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, slots, batch);

        assertEquals(2, batch.strips.size(), "primary/secondary 两个条带分组");
        assertEquals(new EngineInstanceCollector.GroupKey(Stage.FLAME_STRIP, 11), batch.strips.get(0).key());
        assertEquals(new EngineInstanceCollector.GroupKey(Stage.FLAME_STRIP, 22), batch.strips.get(1).key());
        assertEquals(12, batch.strips.get(0).instances().size(), "两个 primary 槽合入同组");
        assertEquals(6, batch.strips.get(1).instances().size());

        assertEquals(1, batch.cores.size());
        assertEquals(new EngineInstanceCollector.GroupKey(Stage.FLAME_CORE, 33), batch.cores.get(0).key());
        assertEquals(3, batch.cores.get(0).instances().size());

        assertEquals(1, batch.glows.size());
        assertEquals(new EngineInstanceCollector.GroupKey(Stage.GLOW_SPRITE, 44), batch.glows.get(0).key());
        assertEquals(6, batch.glows.get(0).instances().size(), "3 槽 × 外圈+白芯");
    }

    @Test
    void emptySlotsProduceEmptyBatch() {
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(shipFrame(), List.of(), batch);
        assertTrue(batch.isEmpty());
    }

    // ------------------------------------------------------------------
    // alpha 截断边界
    // ------------------------------------------------------------------

    @Test
    void glColorByteReplicatesVanillaTruncationAndWrap() {
        assertEquals(254, EngineInstanceCollector.glColorByte(254.99999f), "int 截断而非四舍五入");
        assertEquals(255, EngineInstanceCollector.glColorByte(255.0f));
        assertEquals(255, EngineInstanceCollector.glColorByte(255.9f));
        assertEquals(0, EngineInstanceCollector.glColorByte(0.999f));
        assertEquals(0, EngineInstanceCollector.glColorByte(-0.5f), "负值向零截断");
        assertEquals(0, EngineInstanceCollector.glColorByte(256.0f), "(byte) 取低 8 位回绕");
        assertEquals(44, EngineInstanceCollector.glColorByte(300.5f), "300 → (byte)44");
        assertEquals(255, EngineInstanceCollector.glColorByte(-1.5f), "-1 → (byte)0xFF");
    }

    // ------------------------------------------------------------------
    // VBO 展开与索引
    // ------------------------------------------------------------------

    private static float[] refRotate(float x, float y, float deg) {
        float radians = deg * 0.017453292519943295769f;
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        return new float[]{x * cos - y * sin, x * sin + y * cos};
    }

    @Test
    void expandStripVerticesMatchesVanillaMatrixChainBitExact() {
        StripInstance in = new StripInstance(
                3.0f, 4.0f, 45.0f,
                -0.5f, 2.0f, 0.3f, 0.75f, 0.8f,
                3.5f, 1.7f, 7.0f,
                0.3f, 0.25f, 0.5f,
                255, 128, 64, 15, 100, 11);

        ByteBuffer buf = ByteBuffer.allocate(6 * EngineInstanceCollector.VERTEX_BYTES)
                                   .order(ByteOrder.nativeOrder());
        EngineInstanceCollector.expandStripVertices(in, buf);
        buf.flip();

        for (int vi = 0; vi < 6; vi++) {
            float px = vi < 2 ? 0.0f : (vi < 4 ? 1.7f : 7.0f);
            float py = (vi & 1) == 0 ? -3.5f : 3.5f;
            float x = px * 0.75f + 0.3f;
            float y = py * 0.8f;
            float[] p = refRotate(x, y, 2.0f);
            p = refRotate(p[0], p[1], -0.5f);
            p = refRotate(p[0], p[1], 45.0f);
            float expectedU = vi < 2 ? 0.3f : (vi < 4 ? 0.3f + 0.25f : 0.3f + 0.5f);
            float expectedV = (vi & 1) == 0 ? 0.01f : 0.99f;
            int expectedAlpha = vi < 2 ? 15 : (vi < 4 ? 100 : 0);

            int base = vi * EngineInstanceCollector.VERTEX_BYTES;
            assertFloatBits(3.0f + p[0], buf.getFloat(base), "x[" + vi + "]");
            assertFloatBits(4.0f + p[1], buf.getFloat(base + 4), "y[" + vi + "]");
            assertFloatBits(expectedU, buf.getFloat(base + 8), "u[" + vi + "]");
            assertFloatBits(expectedV, buf.getFloat(base + 12), "v[" + vi + "]");
            assertEquals((byte) 255, buf.get(base + 16));
            assertEquals((byte) 128, buf.get(base + 17));
            assertEquals((byte) 64, buf.get(base + 18));
            assertEquals((byte) expectedAlpha, buf.get(base + 19));
        }
    }

    @Test
    void expandCoreVerticesAppliesOmegaScaleCoreRotationChain() {
        CoreInstance in = new CoreInstance(
                3.0f, 4.0f, 45.0f, 0.2f, -0.1f,
                7.0f, 3.5f,
                255, 128, 64, 25, 33);

        ByteBuffer buf = ByteBuffer.allocate(4 * EngineInstanceCollector.VERTEX_BYTES)
                                   .order(ByteOrder.nativeOrder());
        EngineInstanceCollector.expandCoreVertices(in, buf);
        buf.flip();

        for (int vi = 0; vi < 4; vi++) {
            float px = vi < 2 ? 0.0f : 7.0f;
            float py = (vi & 1) == 0 ? -3.5f : 3.5f;
            float[] p = refRotate(px, py, -0.1f);
            p[0] *= 0.9f;
            p = refRotate(p[0], p[1], 0.2f);
            p = refRotate(p[0], p[1], 45.0f);

            int base = vi * EngineInstanceCollector.VERTEX_BYTES;
            assertFloatBits(3.0f + p[0], buf.getFloat(base), "x[" + vi + "]");
            assertFloatBits(4.0f + p[1], buf.getFloat(base + 4), "y[" + vi + "]");
            assertFloatBits(vi < 2 ? 0.01f : 0.99f, buf.getFloat(base + 8), "u[" + vi + "]");
            assertFloatBits((vi & 1) == 0 ? 0.01f : 0.99f, buf.getFloat(base + 12), "v[" + vi + "]");
            assertEquals((byte) 25, buf.get(base + 19));
        }
    }

    @Test
    void expandGlowVerticesMatchesSpriteQuadLayout() {
        GlowInstance in = new GlowInstance(
                3.0f, 4.0f, 45.0f, 0.2f, 0.9f,
                40.0f,
                0.0f, 0.0f, 1.0f, 1.0f,
                255, 128, 64, 114, 44);

        ByteBuffer buf = ByteBuffer.allocate(4 * EngineInstanceCollector.VERTEX_BYTES)
                                   .order(ByteOrder.nativeOrder());
        EngineInstanceCollector.expandGlowVertices(in, buf);
        buf.flip();

        float[] expectedU = {0.0f, 0.0f, 1.0f, 1.0f};
        float[] expectedV = {0.0f, 1.0f, 1.0f, 0.0f};
        for (int vi = 0; vi < 4; vi++) {
            float cx = vi == 2 || vi == 3 ? 40.0f : 0.0f;
            float cy = vi == 1 || vi == 2 ? 40.0f : 0.0f;
            float qx = (cx - 20.0f) * 0.9f;
            float qy = cy - 20.0f;
            float[] p = refRotate(qx, qy, 0.2f);
            p = refRotate(p[0], p[1], 45.0f);

            int base = vi * EngineInstanceCollector.VERTEX_BYTES;
            assertFloatBits(3.0f + p[0], buf.getFloat(base), "x[" + vi + "]");
            assertFloatBits(4.0f + p[1], buf.getFloat(base + 4), "y[" + vi + "]");
            assertFloatBits(expectedU[vi], buf.getFloat(base + 8), "u[" + vi + "]");
            assertFloatBits(expectedV[vi], buf.getFloat(base + 12), "v[" + vi + "]");
            assertEquals((byte) 114, buf.get(base + 19));
        }
    }

    @Test
    void indexSequencesMatchQuadStripAndQuadTopology() {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        EngineInstanceCollector.appendStripIndices(buf, 6);
        buf.flip();
        int[] expectedStrip = {6, 7, 9, 6, 9, 8, 8, 9, 11, 8, 11, 10};
        for (int expected : expectedStrip) {
            assertEquals(expected, buf.getShort(), "条带索引序列");
        }

        buf.clear();
        EngineInstanceCollector.appendCoreIndices(buf, 4);
        buf.flip();
        int[] expectedCore = {4, 5, 7, 4, 7, 6};
        for (int expected : expectedCore) {
            assertEquals(expected, buf.getShort(), "核心索引序列");
        }

        buf.clear();
        EngineInstanceCollector.appendGlowIndices(buf, 8);
        buf.flip();
        int[] expectedGlow = {8, 9, 10, 8, 10, 11};
        for (int expected : expectedGlow) {
            assertEquals(expected, buf.getShort(), "辉光索引序列");
        }
    }

    // ------------------------------------------------------------------
    // INSTANCED 属性打包
    // ------------------------------------------------------------------

    @Test
    void packStripInstanceLaysOutFieldsInAttributeOrder() {
        StripInstance in = new StripInstance(
                1.0f, 2.0f, 3.0f, 4.0f,
                5.0f, 6.0f, 7.0f, 8.0f,
                9.0f, 10.0f, 11.0f, 12.0f,
                13.0f, 14.0f,
                15, 16, 17, 18, 19, 20);

        java.nio.FloatBuffer buf = ByteBuffer.allocateDirect(EngineInstanceCollector.STRIP_INSTANCE_FLOATS * 4)
                                             .order(ByteOrder.nativeOrder()).asFloatBuffer();
        EngineInstanceCollector.packStripInstance(in, buf);
        buf.flip();

        float[] expected = {
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 18, 19,
                15, 16, 17, 0
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], buf.get(i), "打包字段偏移 " + i);
        }
    }

    @Test
    void packCoreAndGlowInstancesLayOutFieldsInAttributeOrder() {
        CoreInstance core = new CoreInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        java.nio.FloatBuffer coreBuf = ByteBuffer.allocateDirect(EngineInstanceCollector.CORE_INSTANCE_FLOATS * 4)
                                                 .order(ByteOrder.nativeOrder()).asFloatBuffer();
        EngineInstanceCollector.packCoreInstance(core, coreBuf);
        coreBuf.flip();
        float[] expectedCore = {1, 2, 3, 4, 5, 6, 7, 11, 8, 9, 10, 0};
        for (int i = 0; i < expectedCore.length; i++) {
            assertEquals(expectedCore[i], coreBuf.get(i), "核心打包字段偏移 " + i);
        }

        GlowInstance glow = new GlowInstance(1, 2, 3, 4, 0.9f, 40, 0, 0, 1, 1, 5, 6, 7, 8, 9);
        java.nio.FloatBuffer glowBuf = ByteBuffer.allocateDirect(EngineInstanceCollector.GLOW_INSTANCE_FLOATS * 4)
                                                 .order(ByteOrder.nativeOrder()).asFloatBuffer();
        EngineInstanceCollector.packGlowInstance(glow, glowBuf);
        glowBuf.flip();
        float[] expectedGlow = {1, 2, 3, 4, 0.9f, 40, 8, 0, 0, 0, 1, 1, 5, 6, 7, 0};
        for (int i = 0; i < expectedGlow.length; i++) {
            assertEquals(expectedGlow[i], glowBuf.get(i), "辉光打包字段偏移 " + i);
        }
    }
}
