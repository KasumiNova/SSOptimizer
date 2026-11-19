package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.starfarer.combat.entities.ContrailEngine;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContrailBatchHelperTest {
    private record Vertex(float x, float y, float u, float v, int r, int g, int b, int a) {
    }

    private static Vertex vertex(ByteBuffer strip, int index) {
        int offset = index * 20;
        return new Vertex(strip.getFloat(offset), strip.getFloat(offset + 4),
                strip.getFloat(offset + 8), strip.getFloat(offset + 12),
                strip.get(offset + 16) & 255, strip.get(offset + 17) & 255,
                strip.get(offset + 18) & 255, strip.get(offset + 19) & 255);
    }

    @Test
    void expansionUsesCapturedValuesAfterGameAdvances() {
        FakeSegment seg = segment(10, 20, 1, 0, 4, 1, 0.5f, 1, 0.4f);
        FakeGroup group = groupWith(seg, new Vector2f(30, 40));
        Samples first = new Samples(1f);
        Samples second = new Samples(0.5f);
        try {
            first.capture(group, group.segments);
            seg.position.set(100, 0);
            seg.normal.set(0, 1);
            seg.width = 2;
            seg.u = 0.8f;
            seg.progress = 0.25f;
            seg.alphaMult = 0.5f;
            group.tail.set(150, 0);
            group.tail = null;
            group.color = new Color(10, 20, 30, 255);
            group.segmentDuration = 0.1f;
            second.capture(group, group.segments);
            group.segments.clear();

            ByteBuffer strip = first.expand();
            assertEquals(4 * 20, strip.remaining());
            assertEquals(new Vertex(8, 20, 0.4f, 0.01f, 255, 128, 64, 105), vertex(strip, 0));
            assertEquals(new Vertex(12, 20, 0.4f, 0.99f, 255, 128, 64, 105), vertex(strip, 1));
            assertEquals(new Vertex(29, 40, 0.4f, 0.01f, 255, 128, 64, 0), vertex(strip, 2));
            assertEquals(new Vertex(31, 40, 0.4f, 0.99f, 255, 128, 64, 0), vertex(strip, 3));

            strip = second.expand();
            assertEquals(2 * 20, strip.remaining());
            assertEquals(new Vertex(100, -1, 0.8f, 0.01f, 10, 20, 30, 159), vertex(strip, 0));
            assertEquals(new Vertex(100, 1, 0.8f, 0.99f, 10, 20, 30, 159), vertex(strip, 1));
        } finally {
            first.discard();
            second.discard();
        }
    }

    @Test
    void joinsUseThePreviousRightVertexWithOrWithoutATail() {
        FakeGroup first = groupWith(segment(10, 20, 1, 0, 4, 1, 0.5f, 1, 0.5f), new Vector2f(30, 40));
        FakeGroup next = groupWith(new Color(10, 20, 30, 255),
                segment(100, 0, 0, 1, 2, 1, 0.2f, 1, 0.8f), null);
        FakeGroup invisible = new FakeGroup();
        FakeSegment invalid = new FakeSegment();
        next.segments.add(invalid);
        Samples batch = new Samples(1);
        try {
            batch.capture(first, first.segments);
            batch.capture(invisible, invisible.segments);
            batch.capture(next, next.segments);
            batch.capture(first, first.segments);
            ByteBuffer strip = batch.expand();
            assertEquals(14 * 20, strip.remaining());
            assertEquals(vertex(strip, 3), vertex(strip, 4));
            assertEquals(vertex(strip, 3), vertex(strip, 5));
            assertEquals(new Vertex(100, -1, 0.8f, 0.01f, 10, 20, 30, 214), vertex(strip, 6));
            assertEquals(vertex(strip, 7), vertex(strip, 8));
            assertEquals(vertex(strip, 7), vertex(strip, 9));
            assertEquals(vertex(strip, 0), vertex(strip, 10));
        } finally {
            batch.discard();
        }
    }

    @Test
    void groupAfterAFullDrawStartsWithoutConnector() {
        FakeSegment repeated = segment(0, 0, 1, 0, 2, 1, 0.5f, 1, 0.25f);
        FakeGroup first = groupWith(repeated, null);
        FakeGroup next = groupWith(segment(100, 10, 0, 1, 4, 1, 0.5f, 1, 0.75f), null);
        List<Object> fullDraw = java.util.Collections.nCopies(131_072, repeated);
        Samples batch = new Samples(1);
        try {
            batch.capture(first, fullDraw);
            assertEquals(0, batch.draws.size());
            batch.capture(next, next.segments);
            assertEquals(1, batch.draws.size());
            assertEquals(2 * fullDraw.size() * 20, batch.draws.getFirst().expand().remaining());
            batch.recorder.flush();
            ByteBuffer strip = batch.draws.getLast().expand();
            assertEquals(2 * 20, strip.remaining());
            assertEquals(new Vertex(100, 8, 0.75f, 0.01f, 255, 128, 64, 105), vertex(strip, 0));
        } finally {
            batch.discard();
        }
    }

    @Test
    void expandedStripsMatchSegmentFormulaAcrossGroupAndBufferBoundaries() {
        Random random = new Random(0xCAFE_BEEFL);
        float[] durations = {0, -1, 0.1f, 1, 5};
        for (int trial = 0; trial < 100; trial++) {
            float scale = random.nextFloat() * 2;
            Samples batch = new Samples(scale);
            List<Vertex> expected = new ArrayList<>();
            try {
                for (int g = 0; g < 5; g++) {
                    FakeGroup group = new FakeGroup();
                    group.segmentDuration = durations[(trial + g) % durations.length];
                    group.color = new Color(random.nextInt(), true);
                    int count = 1 + random.nextInt(40);
                    if (!expected.isEmpty()) {
                        Vertex last = expected.getLast();
                        expected.add(last);
                        expected.add(last);
                    }
                    for (int i = 0; i < count; i++) {
                        FakeSegment s = segment(random.nextFloat() * 100, random.nextFloat() * 100,
                                random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 10,
                                group.segmentDuration, random.nextFloat() * 1.5f, random.nextFloat() * 1.5f,
                                random.nextFloat());
                        group.segments.add(s);
                        float window = s.maxAge <= 0 ? 0.5f : Math.min(0.5f, 0.05f / s.maxAge);
                        float brightness = s.progress < window ? s.progress * 10 : (1 - s.progress) / (1 - window);
                        int alpha = Math.max(0, Math.min(255, (int) (group.color.getAlpha() * s.alphaMult * (brightness * scale))));
                        expected.add(expectedVertex(s, group.color, s.position, -0.5f, alpha));
                        expected.add(expectedVertex(s, group.color, s.position, 0.5f, alpha));
                        if (i == count - 1 && random.nextBoolean()) {
                            group.tail = new Vector2f(random.nextFloat() * 100, random.nextFloat() * 100);
                            expected.add(expectedVertex(s, group.color, group.tail, -0.25f, 0));
                            expected.add(expectedVertex(s, group.color, group.tail, 0.25f, 0));
                        }
                    }
                    batch.capture(group, group.segments);
                }
                ByteBuffer strip = batch.expand();
                assertEquals(expected.size() * 20, strip.remaining());
                for (int i = 0; i < expected.size(); i++) {
                    assertEquals(expected.get(i), vertex(strip, i));
                }
            } finally {
                batch.discard();
            }
        }
    }

    private static Vertex expectedVertex(FakeSegment segment, Color color, Vector2f center, float widthScale, int alpha) {
        float w = segment.width * widthScale;
        return new Vertex(center.x + segment.normal.x * w, center.y + segment.normal.y * w,
                segment.u, widthScale < 0 ? 0.01f : 0.99f, color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static final class Samples {
        final List<ContrailBatch> draws = new ArrayList<>();
        final ContrailRecorder recorder;

        Samples(float scale) {
            recorder = new ContrailRecorder(scale, draws::add);
        }

        void capture(FakeGroup group, List<Object> segments) {
            recorder.capture(group, segments);
        }

        ByteBuffer expand() {
            recorder.flush();
            assertEquals(1, draws.size());
            return draws.getFirst().expand();
        }

        void discard() {
            recorder.close();
            draws.forEach(ContrailBatch::discard);
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
