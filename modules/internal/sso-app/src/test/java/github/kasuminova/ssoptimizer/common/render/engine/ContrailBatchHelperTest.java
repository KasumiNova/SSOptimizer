package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.starfarer.combat.entities.ContrailEngine;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ContrailBatchHelperTest {

    @BeforeEach
    void resetBatch() {
        ContrailBatchHelper.beginStrip();
    }

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

    @Test
    void singleGroupEncodesSegmentThenTailPair() {
        FakeGroup group = groupWith(
                segment(10f, 20f, 1f, 0f, 4f, 1f, 0.5f, 1f, 0.5f),
                new Vector2f(30f, 40f));

        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(4, ContrailBatchHelper.getNumVertices());

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

    @Test
    void fadeInSegmentAlphaUsesProgressTimesTen() {
        FakeGroup group = groupWith(
                segment(0f, 0f, 1f, 0f, 2f, 1f, 0.02f, 1f, 0.25f),
                null);
        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(2, ContrailBatchHelper.getNumVertices());
        ContrailBatchHelper.EncodedVertex v0 = ContrailBatchHelper.vertexAt(0);
        assertEquals(39, v0.a());
    }

    @Test
    void alphaScaleScalesSegmentBrightness() {
        FakeGroup group = groupWith(
                segment(0f, 0f, 1f, 0f, 2f, 1f, 0.02f, 1f, 0.25f),
                null);
        assertTrue(ContrailBatchHelper.encodeGroup(group, 0.5f));
        assertEquals(19, ContrailBatchHelper.vertexAt(0).a());
    }

    @Test
    void groupWithNullColorEmitsNoVertices() {
        FakeGroup group = new FakeGroup();
        group.color = null;
        group.tail = new Vector2f(0f, 0f);
        group.segments.add(segment(1f, 1f, 1f, 0f, 2f, 1f, 0.5f, 1f, 0.25f));

        assertTrue(ContrailBatchHelper.encodeGroup(group, 1f));
        assertEquals(0, ContrailBatchHelper.getNumVertices());
    }

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

    @Test
    void hoistedFadeWindowMatchesPerSegmentFormulaAcrossRandomStates() {
        Random random = new Random(0xCAFE_BEEFL);
        float[] edgeDurations = {0f, -1f, 0.1f, 1f, 5f};

        for (int trial = 0; trial < 300; trial++) {
            ContrailBatchHelper.beginStrip();

            float segmentDuration;
            if (trial < edgeDurations.length * 20) {
                segmentDuration = edgeDurations[trial / 20];
            } else {
                segmentDuration = 0.1f + random.nextFloat() * 5f;
            }

            FakeGroup group = new FakeGroup();
            group.color = new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), 255);
            group.segmentDuration = segmentDuration;
            int baseAlpha = group.color.getAlpha();
            int red = group.color.getRed();
            int green = group.color.getGreen();
            int blue = group.color.getBlue();

            int segmentCount = 1 + random.nextInt(8);
            FakeSegment[] segments = new FakeSegment[segmentCount];
            for (int i = 0; i < segmentCount; i++) {
                FakeSegment s = new FakeSegment();
                s.position = new Vector2f(random.nextFloat() * 100f, random.nextFloat() * 100f);
                s.normal = new Vector2f(random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f);
                s.width = random.nextFloat() * 10f;
                s.maxAge = segmentDuration;
                s.progress = random.nextFloat();
                s.alphaMult = random.nextFloat();
                s.u = random.nextFloat();
                segments[i] = s;
                group.segments.add(s);
            }
            group.tail = new Vector2f(random.nextFloat() * 100f, random.nextFloat() * 100f);
            float alphaScale = random.nextFloat() * 2f;

            int[] expectedAlpha = new int[segmentCount];
            for (int i = 0; i < segmentCount; i++) {
                FakeSegment s = segments[i];
                float fadeWindow = s.maxAge <= 0f ? 0.5f : 0.05f / s.maxAge;
                if (fadeWindow > 0.5f) {
                    fadeWindow = 0.5f;
                }
                float brightness;
                if (s.progress < fadeWindow) {
                    brightness = s.progress * 10f;
                } else {
                    brightness = (1f - s.progress) / (1f - fadeWindow);
                }
                brightness *= alphaScale;
                expectedAlpha[i] = ContrailBatchHelper.clampColorComponent(
                        (int) (baseAlpha * s.alphaMult * brightness));
            }

            assertTrue(ContrailBatchHelper.encodeGroup(group, alphaScale));
            assertEquals(segmentCount * 2 + 2, ContrailBatchHelper.getNumVertices(), "段对 + 尾点对");

            for (int i = 0; i < segmentCount; i++) {
                ContrailBatchHelper.EncodedVertex left = ContrailBatchHelper.vertexAt(i * 2);
                ContrailBatchHelper.EncodedVertex right = ContrailBatchHelper.vertexAt(i * 2 + 1);
                assertEquals(red, left.r(), "段 " + i + " 红色通道");
                assertEquals(green, left.g(), "段 " + i + " 绿色通道");
                assertEquals(blue, left.b(), "段 " + i + " 蓝色通道");
                assertEquals(expectedAlpha[i], left.a(), "段 " + i + " alpha 与逐段公式位级一致");
                assertEquals(expectedAlpha[i], right.a(), "段 " + i + " 右顶点 alpha 与逐段公式位级一致");
            }

            assertEquals(0, ContrailBatchHelper.vertexAt(segmentCount * 2).a());
            assertEquals(0, ContrailBatchHelper.vertexAt(segmentCount * 2 + 1).a());
        }
    }

    private static FakeGroup groupWith(FakeSegment segment, Vector2f tail) {
        return groupWith(new Color(255, 128, 64, 200), segment, tail);
    }

    private static FakeGroup groupWith(Color color, FakeSegment segment, Vector2f tail) {
        FakeGroup group = new FakeGroup();
        group.color = color;
        group.tail = tail;
        group.segmentDuration = 1f;
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
        boolean ended;
        ContrailEngine.ContrailWidthMode widthMode;
        float widthMultiplier;
        float segmentDuration;
        Object key;

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
            return segmentDuration;
        }

        @Override
        public Object ssoptimizer$getKey() {
            return key;
        }

        @Override
        public boolean ssoptimizer$removeExpiredSegment() {
            return false;
        }
    }

    private static final class FakeSegment implements ContrailSegmentAccessor {
        Vector2f position;
        Vector2f normal;
        Vector2f vel = new Vector2f();
        float width;
        float baseWidth;
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
