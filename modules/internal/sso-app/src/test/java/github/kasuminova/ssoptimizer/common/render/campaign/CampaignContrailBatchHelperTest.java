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

/**
 * {@link CampaignContrailBatchHelper} 的编码逻辑与原版 {@code ContrailEngineV2
 * .render(float)} 逐条渲染的等价性验证。
 * <p>
 * 参考实现逐行转写自反编译原版（named 仓 {@code ContrailEngineV2.java:264-416}），
 * 相交判定直接调用真实的 {@link VectorMathUtils#segmentIntersection}（独立验证
 * helper 内联的标量版），GL 调用替换为顶点记录。覆盖：逐点亮度公式（淡入/淡出
 * 双段、首点强制 0）、fadeOut 检测（展开段相交 + 前点折返）与状态写回、
 * proximity 衰减与 lastProximityMult 单调钳制、proximity 参照点跨尾迹存续、
 * 尾点对（alpha 0、宽度 1/4）、同批退化连接点（重复上一组末顶点两次）、
 * 不可绘制尾迹（点数 ≤ 1）的 maxBrightness 清零。
 * GL 侧的 flush/混合切换/纹理绑定编排无 GL 上下文不可触达，由接入游戏后的
 * A/B 验证兜底——本测试只验证纯编码路径与渲染期状态改写。
 */
class CampaignContrailBatchHelperTest {

    @BeforeEach
    void resetBatch() {
        CampaignContrailBatchHelper.beginStrip();
    }

    // ------------------------------------------------------------------
    // 标量工具
    // ------------------------------------------------------------------

    /** 内联标量版相交判定与真实 VectorMathUtils.segmentIntersection 结论一致。 */
    @Test
    void segmentsIntersectMatchesVectorMathUtilsAcrossRandomStates() {
        Random random = new Random(0xC0FF_EEL);
        for (int trial = 0; trial < 20_000; trial++) {
            // 小坐标域提高共线/相交命中率；混入相等坐标制造退化段
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

    // ------------------------------------------------------------------
    // 单条尾迹编码
    // ------------------------------------------------------------------

    /** 单条尾迹 = 点对（V_MIN/V_MAX）+ 尾点对（alpha 0、宽度为点的 1/4）。 */
    @Test
    void singleContrailEncodesPointPairsThenTailPair() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(255, 128, 64, 200));
        contrail.points.add(point(10f, 20f, 1f, 0f, 4f, 2f, 1f, 0.5f));
        contrail.points.add(point(30f, 20f, 1f, 0f, 4f, 2f, 1f, 1.5f));
        contrail.lastPoint = new Vector2f(40f, 20f);

        assertNull(CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null));
        assertEquals(6, CampaignContrailBatchHelper.getNumVertices());

        // 首点：亮度强制 0
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

        // 第二点：elapsed(1) ≥ fadeWindow(min(0.1, duration/2=1)=0.1)，
        // brightness=(2-1)/(2-0.1)=1/1.9；alpha=(int)(200×1×0.52631581)=105
        CampaignContrailBatchHelper.EncodedVertex v2 = CampaignContrailBatchHelper.vertexAt(2);
        assertEquals(28f, v2.x(), 1e-4f);
        assertEquals(1.5f, v2.u(), 1e-4f);
        assertEquals(105, v2.a());
        assertEquals(255, v2.r());
        assertEquals(128, v2.g());
        assertEquals(64, v2.b());

        // 尾点对：lastPoint(40,20) ± perp(1,0) × width/4(1)，alpha 恒 0，u 取末点 texCoord
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

    /** 淡入分支：elapsed < fadeWindow 时 brightness = elapsed / fadeWindow。 */
    @Test
    void fadeInPointAlphaUsesElapsedOverFadeWindow() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(10, 20, 30, 255));
        contrail.points.add(point(0f, 0f, 0f, 1f, 2f, 2f, 0f, 0f));
        // duration=2 → fadeWindow=0.1；elapsed=0.05 → brightness=0.5
        contrail.points.add(point(10f, 0f, 0f, 1f, 2f, 2f, 0.05f, 0.25f));

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);
        // alpha = (int)(255 × 1 × 0.5) = 127
        assertEquals(127, CampaignContrailBatchHelper.vertexAt(2).a());
    }

    /** alphaMult 全局缩放参与亮度计算（原版 render(float) 的 var1 语义）。 */
    @Test
    void alphaMultScalesPointBrightness() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(10, 20, 30, 255));
        contrail.points.add(point(0f, 0f, 0f, 1f, 2f, 2f, 0f, 0f));
        contrail.points.add(point(10f, 0f, 0f, 1f, 2f, 2f, 0.05f, 0.25f));

        CampaignContrailBatchHelper.encodeContrail(contrail, 0.5f, null);
        // brightness=0.5×0.5=0.25；alpha=(int)(255×0.25)=63
        assertEquals(63, CampaignContrailBatchHelper.vertexAt(2).a());
    }

    /** 无尾点（lastPoint == null）：只有点对。 */
    @Test
    void contrailWithoutLastPointEmitsNoTailPair() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(1, 2, 3, 255));
        contrail.points.add(point(0f, 0f, 1f, 0f, 2f, 2f, 0f, 0f));
        contrail.points.add(point(10f, 0f, 1f, 0f, 2f, 2f, 1f, 0.5f));
        contrail.lastPoint = null;

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);
        assertEquals(4, CampaignContrailBatchHelper.getNumVertices());
    }

    /** fadeOut 检测：与后点展开段相交时写回 fadeOut/origMax/elapsedWhenFadeOut。 */
    @Test
    void intersectionWithNextMarksFadeOutAndSnapshotsState() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(10, 20, 30, 255));
        // 三点折线回头：p1 的展开段与 p2 的展开段必相交（同位置、大宽度）
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

    /**
     * proximity 衰减：fadeOut 参照点跨尾迹存续；lastProximityMult 单调下降钳制
     * 并写回；近距离点亮度被压低。参照点的 fadeOut 态预设（origMax 0.8、
     * maxBrightness 0.4，模拟 advance 的 fadeOut 衰减后状态），几何取单调折线 +
     * 小宽度，不触发本测试不关注的相交/折返检测。
     */
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
        // 距 fadeSource(10,0) 约 190 → 超出 50 → mult=1（不衰减）
        ContrailEngineV2.ContrailPoint far = point(200f, 1f, 0f, 1f, 2f, 3f, 2f, 0.5f);
        far.maxBrightness = 1f;
        second.points.add(far);
        // 距 fadeSource 距离 0.5 → fadeRatio=1-0.4/0.8=0.5，mult=0.5+0.5×0.01=0.505
        ContrailEngineV2.ContrailPoint near = point(10f, 0.5f, 0f, 1f, 2f, 3f, 2f, 0.75f);
        near.maxBrightness = 1f;
        second.points.add(near);

        ContrailEngineV2.ContrailPoint fadeSource =
                CampaignContrailBatchHelper.encodeContrail(first, 1f, null);
        assertSame(source, fadeSource, "fadeOut 点应成为后续尾迹的 proximity 参照");
        fadeSource = CampaignContrailBatchHelper.encodeContrail(second, 1f, fadeSource);
        assertSame(source, fadeSource, "第二条尾迹无 fadeOut 点时参照不变");

        // far 点（第二条第 2 点，左顶点索引 6+2+2=10）：brightness=(3-2)/(3-0.1)，
        // mult=1 → alpha=(int)(255×1/2.9)=87
        assertEquals(87, CampaignContrailBatchHelper.vertexAt(10).a());
        // near 点（第二条第 3 点，左顶点索引 12）：mult=0.505，
        // alpha=(int)(255×(1/2.9)×0.505)=44
        assertEquals(44, CampaignContrailBatchHelper.vertexAt(12).a());
        assertEquals(0.505f, near.lastProximityMult, 1e-3f, "lastProximityMult 写回");
        assertEquals(1f, far.lastProximityMult, 1e-6f, "远距离点不衰减");
    }

    // ------------------------------------------------------------------
    // 批次合并（连接点）
    // ------------------------------------------------------------------

    /**
     * 同批尾迹拼接进同一条 strip：连接点 = 重复上一组末顶点两次（GL_QUAD_STRIP
     * 退化四边形，零面积不产生碎片），随后新尾迹的点对继续。
     */
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

        // 连接点：v6 = v7 = 第一条尾迹末顶点（尾点右顶点 v5）
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

        // 第二条首点对从 v8 继续
        CampaignContrailBatchHelper.EncodedVertex v8 = CampaignContrailBatchHelper.vertexAt(8);
        assertEquals(100f, v8.x(), 1e-4f);
        assertEquals(-1f, v8.y(), 1e-4f);
        assertEquals(0.8f, v8.u(), 1e-4f);
        assertEquals(0.01f, v8.v(), 1e-4f);
    }

    /** 批次首条无连接点：v0 就是首点左顶点。 */
    @Test
    void firstContrailOfBatchHasNoJoinConnector() {
        ContrailEngineV2.Contrail contrail = contrail(new Color(1, 2, 3, 255));
        contrail.points.add(point(10f, 20f, 1f, 0f, 4f, 2f, 0f, 0.5f));
        contrail.points.add(point(30f, 20f, 1f, 0f, 4f, 2f, 1f, 1.5f));

        CampaignContrailBatchHelper.encodeContrail(contrail, 1f, null);
        assertEquals(4, CampaignContrailBatchHelper.getNumVertices());
        assertEquals(8f, CampaignContrailBatchHelper.vertexAt(0).x(), 1e-4f);
    }

    /** beginStrip 重置批次：连接点跟踪不跨批次复用末顶点。 */
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

    // ------------------------------------------------------------------
    // 随机状态位级等价（对照独立转写的原版参考实现）
    // ------------------------------------------------------------------

    /**
     * 随机尾迹集上，helper 编码输出（顶点位置/UV/颜色字节）与渲染期状态改写
     * （fadeOut/origMax/elapsedWhenFadeOut/lastProximityMult/maxBrightness）必须
     * 与原版参考实现位级一致。连接点按「重复上一可绘制尾迹末顶点两次」插入期望
     * 序列。全部尾迹同混合模式（GLOW），不触发 GL 耦合的批次 flush。
     */
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

            // 参考路径：原版逐条渲染（GL 调用替换为顶点记录）
            List<RecordedVertex> expected = new ArrayList<>();
            ContrailEngineV2.ContrailPoint refFadeSource = null;
            for (ContrailEngineV2.Contrail contrail : reference) {
                if (contrail.points.size() > 1) {
                    if (!expected.isEmpty()) {
                        // 连接点：重复上一可绘制尾迹的末顶点两次
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

            // 优化路径
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

    /**
     * 包围圆拒绝路径（R1）的位级对照：相邻点距 100~1000、宽度 0.5~4
     * （半径和 ≤ 6，远小于点距），包围圆拒绝恒命中，展开段数学与 segmentsIntersect
     * 被整体跳过。编码输出与 fadeOut/proximity 状态写回必须与逐行转写的原版参考
     * 实现（真实 {@link VectorMathUtils#segmentIntersection}）位级一致——几何上
     * 圆不相交 ⇒ 展开段必不相交 ⇒ 参考实现结论恒为 false、markFadeOut 恒不触发。
     */
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
                    // 连接点：重复上一可绘制尾迹的末顶点两次（稀疏尾迹恒可绘制）
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

    /**
     * 稀疏尾迹：相邻点距 100~1000（单轴步进），宽度 0.5~4（包围圆半径和 ≤ 6，
     * 拒绝恒命中）；混入 fadeOut 预设态覆盖 proximity 写回。点数 2~6（恒可绘制）。
     */
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

    // ------------------------------------------------------------------
    // 参考实现：逐行转写反编译原版 render(float) 内层（GL 调用替换为记录）
    // 相交判定调用真实 VectorMathUtils.segmentIntersection，独立验证标量版。
    // ------------------------------------------------------------------

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

            // RenderStateUtils.setGlColor(color, (int)(alpha*maxBrightness*var12))
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

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

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

    /**
     * 随机尾迹：点列小步随机游 + 大宽度，提高展开段相交与折返判定命中率；
     * 覆盖 0~6 点（含不可绘制分支）、duration=0 边界、fadeOut 预设态。
     */
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
