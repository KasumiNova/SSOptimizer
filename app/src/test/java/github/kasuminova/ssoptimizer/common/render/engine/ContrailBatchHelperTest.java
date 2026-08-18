package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContrailBatchHelper} 的编码逻辑验证。
 * <p>
 * 覆盖批次合并的核心：段对编码（位置/UV/颜色）、尾点对（alpha 0、宽度 1/4）、
 * 同混合模式组拼接进同一条 strip 时的退化连接点（重复上一组末顶点两次）、
 * 首组无连接点、不可绘制组不产生顶点。GL 侧的 flush/混合切换/纹理绑定编排
 * 走 {@code org.lwjgl.opengl.GL11}（生产由重定向器改写到 bridge 录制），无 GL
 * 上下文环境下不可触达，其命令结构由 bridge 层单测（GL11BridgeTest）与接入
 * 游戏后的 A/B 验证兜底——本测试只验证纯编码路径（含连接点的时序布局）。
 */
class ContrailBatchHelperTest {

    @BeforeEach
    void resetBatch() {
        ContrailBatchHelper.beginStrip();
    }

    // ------------------------------------------------------------------
    // 标量工具
    // ------------------------------------------------------------------

    @Test
    void clampColorComponentKeepsAlphaInByteRange() {
        assertEquals(0, ContrailBatchHelper.clampColorComponent(-12));
        assertEquals(42, ContrailBatchHelper.clampColorComponent(42));
        assertEquals(255, ContrailBatchHelper.clampColorComponent(999));
    }

    @Test
    void glowBlendModeUsesEnumNameMatching() {
        assertTrue(ContrailBatchHelper.isGlowBlendMode(EngineSlot.BlendMode.GLOW));
        assertFalse(ContrailBatchHelper.isGlowBlendMode(EngineSlot.BlendMode.SMOKE));
        assertFalse(ContrailBatchHelper.isGlowBlendMode("GLOW"));
        assertTrue(ContrailBatchHelper.isGlowBlendMode(FakeBlendMode.GLOW));
    }

    private enum FakeBlendMode {
        GLOW,
        NORMAL
    }

    // ------------------------------------------------------------------
    // 单组编码
    // ------------------------------------------------------------------

    /**
     * 单组 = 段对（V_MIN/V_MAX）+ 尾点对（alpha 0、宽度为段的 1/4）。
     * 颜色 alpha 来自 fadeWindow 亮度公式：progress ≥ fadeWindow 时
     * brightness = (1 - progress) / (1 - fadeWindow)。
     */
    @Test
    void singleGroupEncodesSegmentThenTailPair() {
        FakeGroup group = groupWith(
                segment(10f, 20f, 1f, 0f, 4f, 1f, 0.5f, 1f, 0.5f),
                new Vector2f(30f, 40f));

        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(4, ContrailBatchHelper.getNumVertices());

        // 段：position(10,20) ± normal(1,0) × halfWidth(2)
        ContrailBatchHelper.EncodedVertex v0 = ContrailBatchHelper.vertexAt(0);
        assertEquals(8f, v0.x(), 1e-4f);
        assertEquals(20f, v0.y(), 1e-4f);
        assertEquals(0.5f, v0.u(), 1e-4f);
        assertEquals(0.01f, v0.v(), 1e-4f);
        assertEquals(255, v0.r());
        assertEquals(128, v0.g());
        assertEquals(64, v0.b());
        assertEquals(105, v0.a(), "brightness=0.5/0.95，baseAlpha 200 → 105");

        ContrailBatchHelper.EncodedVertex v1 = ContrailBatchHelper.vertexAt(1);
        assertEquals(12f, v1.x(), 1e-4f);
        assertEquals(20f, v1.y(), 1e-4f);
        assertEquals(0.5f, v1.u(), 1e-4f);
        assertEquals(0.99f, v1.v(), 1e-4f);
        assertEquals(105, v1.a());

        // 尾点：tail(30,40) ± normal(1,0) × (width×0.25=1)，alpha 恒 0
        // （原版尾点偏移 = width/4，作为左右各 1 的半个偏移直接使用）
        ContrailBatchHelper.EncodedVertex v2 = ContrailBatchHelper.vertexAt(2);
        assertEquals(29f, v2.x(), 1e-4f);
        assertEquals(40f, v2.y(), 1e-4f);
        assertEquals(0, v2.a());

        ContrailBatchHelper.EncodedVertex v3 = ContrailBatchHelper.vertexAt(3);
        assertEquals(31f, v3.x(), 1e-4f);
        assertEquals(40f, v3.y(), 1e-4f);
        assertEquals(0.5f, v3.u(), 1e-4f);
        assertEquals(0.99f, v3.v(), 1e-4f);
        assertEquals(0, v3.a());
    }

    /** 亮度公式的另一分支：progress < fadeWindow 时 brightness = progress × 10。 */
    @Test
    void fadeInSegmentAlphaUsesProgressTimesTen() {
        FakeGroup group = groupWith(
                segment(0f, 0f, 1f, 0f, 2f, 1f, 0.02f, 1f, 0.25f),
                null);
        // 无尾点：只有段对
        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(2, ContrailBatchHelper.getNumVertices());
        // brightness = 0.02 × 10 = 0.1999999955f；baseAlpha 200 × = 39.9999991 → 39
        ContrailBatchHelper.EncodedVertex v0 = ContrailBatchHelper.vertexAt(0);
        assertEquals(39, v0.a());
    }

    /** alphaScale 全局缩放参与 alpha 计算（原版 render(float alphaScale) 语义）。 */
    @Test
    void alphaScaleScalesSegmentBrightness() {
        FakeGroup group = groupWith(
                segment(0f, 0f, 1f, 0f, 2f, 1f, 0.02f, 1f, 0.25f),
                null);
        assertTrue(ContrailBatchHelper.encodeGroup(group, 0.5f));
        // brightness = 0.02×10×0.5 = 0.0999999978f；200 × = 19.9999995 → 19
        assertEquals(19, ContrailBatchHelper.vertexAt(0).a());
    }

    /** 颜色为 null 的组不可绘制：不产生任何顶点，也不视为批次异常。 */
    @Test
    void groupWithNullColorEmitsNoVertices() {
        FakeGroup group = new FakeGroup();
        group.color = null;
        group.tail = new Vector2f(0f, 0f);
        group.segments.add(segment(1f, 1f, 1f, 0f, 2f, 1f, 0.5f, 1f, 0.25f));

        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(0, ContrailBatchHelper.getNumVertices());
    }

    /** 段 position/normal 为 null 的段被跳过（原版行为），不打断后续段。 */
    @Test
    void segmentWithNullGeometryIsSkipped() {
        FakeSegment invalid = new FakeSegment();
        invalid.position = null;
        invalid.normal = null;
        FakeGroup group = groupWith(
                new Color(255, 128, 64, 200),
                segment(10f, 10f, 1f, 0f, 2f, 1f, 0.5f, 1f, 0.25f),
                null);
        group.segments.add(0, invalid);

        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(2, ContrailBatchHelper.getNumVertices(), "仅有效段被编码");
        ContrailBatchHelper.EncodedVertex v0 = ContrailBatchHelper.vertexAt(0);
        assertEquals(9f, v0.x(), 1e-4f);
    }

    // ------------------------------------------------------------------
    // 批次合并（连接点）
    // ------------------------------------------------------------------

    /**
     * 同混合模式组拼接进同一条 strip：连接点 = 重复上一组末顶点两次
     * （GL_QUAD_STRIP 退化四边形，零面积不产生碎片），随后新组的段对继续。
     * 连接点不改变末顶点跟踪（下一组连接点仍引用本组尾点右顶点）。
     */
    @Test
    void joiningGroupsRepeatsLastVertexTwiceAsDegenerateConnector() {
        FakeGroup groupA = groupWith(
                segment(10f, 20f, 1f, 0f, 4f, 1f, 0.5f, 1f, 0.5f),
                new Vector2f(30f, 40f));
        FakeGroup groupB = groupWith(
                new Color(10, 20, 30, 255),
                segment(100f, 0f, 0f, 1f, 2f, 1f, 0.2f, 1f, 0.8f),
                new Vector2f(150f, 0f));

        assertTrue(ContrailBatchHelper.encodeGroup(groupA, 1f));
        assertTrue(ContrailBatchHelper.encodeGroup(groupB, 1f));
        assertEquals(10, ContrailBatchHelper.getNumVertices(), "4 + 2 连接点 + 4");

        // 连接点：v4 = v5 = A 的末顶点（尾点右顶点 v3）
        ContrailBatchHelper.EncodedVertex v3 = ContrailBatchHelper.vertexAt(3);
        ContrailBatchHelper.EncodedVertex v4 = ContrailBatchHelper.vertexAt(4);
        ContrailBatchHelper.EncodedVertex v5 = ContrailBatchHelper.vertexAt(5);
        assertEquals(v3.x(), v4.x(), 1e-4f);
        assertEquals(v3.y(), v4.y(), 1e-4f);
        assertEquals(v3.u(), v4.u(), 1e-4f);
        assertEquals(v3.r(), v4.r());
        assertEquals(v3.g(), v4.g());
        assertEquals(v3.b(), v4.b());
        assertEquals(v3.a(), v4.a());
        assertEquals(v4.x(), v5.x(), 1e-4f);
        assertEquals(v4.y(), v5.y(), 1e-4f);

        // B 的段对从 v6 继续（position(100,0) ± normal(0,1) × halfWidth(1)）
        ContrailBatchHelper.EncodedVertex v6 = ContrailBatchHelper.vertexAt(6);
        assertEquals(100f, v6.x(), 1e-4f);
        assertEquals(-1f, v6.y(), 1e-4f);
        assertEquals(0.8f, v6.u(), 1e-4f);
        assertEquals(0.01f, v6.v(), 1e-4f);
        assertEquals(10, v6.r());
        assertEquals(20, v6.g());
        assertEquals(30, v6.b());
        assertEquals(214, v6.a(), "brightness=0.8/0.95，baseAlpha 255 → 214");

        ContrailBatchHelper.EncodedVertex v7 = ContrailBatchHelper.vertexAt(7);
        assertEquals(100f, v7.x(), 1e-4f);
        assertEquals(1f, v7.y(), 1e-4f);
        assertEquals(0.99f, v7.v(), 1e-4f);
    }

    /** 批次首组无连接点：v0 就是首段左顶点，而非上一组的重复顶点。 */
    @Test
    void firstGroupOfBatchHasNoJoinConnector() {
        FakeGroup group = groupWith(
                segment(10f, 20f, 1f, 0f, 4f, 1f, 0.5f, 1f, 0.5f),
                new Vector2f(30f, 40f));
        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(4, ContrailBatchHelper.getNumVertices());
        ContrailBatchHelper.EncodedVertex v0 = ContrailBatchHelper.vertexAt(0);
        assertEquals(8f, v0.x(), 1e-4f);
        assertEquals(20f, v0.y(), 1e-4f);
        assertNotEquals(v0.x(), v0.y(), "首组 v0 是段左顶点，非连接点");
    }

    /** 多组连续拼接：每处连接点都精确重复「当前批次最后一个已编码顶点」。 */
    @Test
    void everyJoinConnectorRepeatsTheBatchTailVertex() {
        FakeGroup groupA = groupWith(
                segment(0f, 0f, 1f, 0f, 2f, 1f, 0.5f, 1f, 0.1f),
                new Vector2f(5f, 0f));
        FakeGroup groupB = groupWith(
                segment(10f, 0f, 0f, 1f, 2f, 1f, 0.5f, 1f, 0.2f),
                new Vector2f(15f, 0f));
        FakeGroup groupC = groupWith(
                segment(20f, 0f, 1f, 0f, 2f, 1f, 0.5f, 1f, 0.3f),
                new Vector2f(25f, 0f));

        ContrailBatchHelper.encodeGroup(groupA, 1f);
        ContrailBatchHelper.encodeGroup(groupB, 1f);
        ContrailBatchHelper.encodeGroup(groupC, 1f);

        // 三组各 4 顶点 + 两处连接点各 2 顶点 = 16
        assertEquals(16, ContrailBatchHelper.getNumVertices());
        ContrailBatchHelper.EncodedVertex v3 = ContrailBatchHelper.vertexAt(3);
        ContrailBatchHelper.EncodedVertex v4 = ContrailBatchHelper.vertexAt(4);
        ContrailBatchHelper.EncodedVertex v5 = ContrailBatchHelper.vertexAt(5);
        assertEquals(v3.x(), v4.x(), 1e-4f);
        assertEquals(v3.y(), v4.y(), 1e-4f);
        assertEquals(v4.x(), v5.x(), 1e-4f);
        assertEquals(v4.y(), v5.y(), 1e-4f);

        ContrailBatchHelper.EncodedVertex v9 = ContrailBatchHelper.vertexAt(9);
        ContrailBatchHelper.EncodedVertex v10 = ContrailBatchHelper.vertexAt(10);
        ContrailBatchHelper.EncodedVertex v11 = ContrailBatchHelper.vertexAt(11);
        assertEquals(v9.x(), v10.x(), 1e-4f);
        assertEquals(v9.y(), v10.y(), 1e-4f);
        assertEquals(v10.x(), v11.x(), 1e-4f);
        assertEquals(v10.y(), v11.y(), 1e-4f);
    }

    /** 无尾点的组拼接：连接点重复上一组的段右顶点（无尾点时最后顶点即末段右顶点）。 */
    @Test
    void joinWithoutTailRepeatsLastSegmentVertex() {
        FakeGroup groupA = groupWith(
                segment(0f, 0f, 1f, 0f, 2f, 1f, 0.5f, 1f, 0.1f),
                null);
        FakeGroup groupB = groupWith(
                segment(10f, 0f, 0f, 1f, 2f, 1f, 0.5f, 1f, 0.2f),
                null);

        ContrailBatchHelper.encodeGroup(groupA, 1f);
        ContrailBatchHelper.encodeGroup(groupB, 1f);
        assertEquals(6, ContrailBatchHelper.getNumVertices(), "2 + 2 连接点 + 2");

        ContrailBatchHelper.EncodedVertex v1 = ContrailBatchHelper.vertexAt(1);
        ContrailBatchHelper.EncodedVertex v2 = ContrailBatchHelper.vertexAt(2);
        ContrailBatchHelper.EncodedVertex v3 = ContrailBatchHelper.vertexAt(3);
        assertEquals(v1.x(), v2.x(), 1e-4f);
        assertEquals(v1.y(), v2.y(), 1e-4f);
        assertEquals(v1.x(), v3.x(), 1e-4f);
        assertEquals(v1.y(), v3.y(), 1e-4f);
    }

    /** beginStrip 重置批次：连接点跟踪与新批次隔离（不跨批次复用末顶点）。 */
    @Test
    void beginStripResetsBatchState() {
        FakeGroup groupA = groupWith(
                segment(0f, 0f, 1f, 0f, 2f, 1f, 0.5f, 1f, 0.1f),
                new Vector2f(5f, 0f));
        FakeGroup groupB = groupWith(
                segment(10f, 0f, 0f, 1f, 2f, 1f, 0.5f, 1f, 0.2f),
                new Vector2f(15f, 0f));

        ContrailBatchHelper.encodeGroup(groupA, 1f);
        ContrailBatchHelper.beginStrip();
        ContrailBatchHelper.encodeGroup(groupB, 1f);

        assertEquals(4, ContrailBatchHelper.getNumVertices(), "新批次首组无连接点");
        ContrailBatchHelper.EncodedVertex v0 = ContrailBatchHelper.vertexAt(0);
        assertEquals(10f, v0.x(), 1e-4f);
        assertEquals(-1f, v0.y(), 1e-4f);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static FakeGroup groupWith(FakeSegment segment, Vector2f tail) {
        return groupWith(new Color(255, 128, 64, 200), segment, tail);
    }

    private static FakeGroup groupWith(Color color, FakeSegment segment, Vector2f tail) {
        FakeGroup group = new FakeGroup();
        group.color = color;
        group.tail = tail;
        group.segments.add(segment);
        return group;
    }

    private static FakeSegment segment(float posX, float posY, float nX, float nY,
                                       float width, float maxAge, float progress,
                                       float alphaMult, float u) {
        FakeSegment segment = new FakeSegment();
        segment.position = new Vector2f(posX, posY);
        segment.normal = new Vector2f(nX, nY);
        segment.width = width;
        segment.maxAge = maxAge;
        segment.progress = progress;
        segment.alphaMult = alphaMult;
        segment.u = u;
        return segment;
    }

    private static final class FakeGroup implements ContrailGroupAccessor {
        final List<Object> segments = new ArrayList<>();
        Color color;
        Vector2f tail;

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
            return tail;
        }

        @Override
        public Color ssoptimizer$getColor() {
            return color;
        }

        @Override
        public EngineSlot.BlendMode ssoptimizer$getBlendMode() {
            return null;
        }
    }

    private static final class FakeSegment implements ContrailSegmentAccessor {
        Vector2f position;
        Vector2f normal;
        float width;
        float maxAge;
        float progress;
        float alphaMult;
        float u;

        @Override
        public Vector2f ssoptimizer$getPosition() {
            return position;
        }

        @Override
        public Vector2f ssoptimizer$getNormal() {
            return normal;
        }

        @Override
        public float ssoptimizer$getWidth() {
            return width;
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
        public float ssoptimizer$getAlphaMult() {
            return alphaMult;
        }

        @Override
        public float ssoptimizer$getU() {
            return u;
        }
    }
}
