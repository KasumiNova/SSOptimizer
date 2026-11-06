package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.*;

class VertexArrayBatchTest {

    private static VertexArrayBatch replay(VertexStream stream) {
        VertexArrayBatch batch = new VertexArrayBatch();
        byte[] data = new byte[stream.length()];
        stream.copyTo(data);
        VertexStream.replay(data, data.length, batch);
        return batch;
    }

    @Test
    void singleQuadSnapshot() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.texCoord2f(0.25f, 0.75f);
        stream.color4ub((byte) 10, (byte) 20, (byte) 30, (byte) 40);
        stream.vertex2f(1.0f, 2.0f);
        stream.vertex2f(3.0f, 2.0f);
        stream.texCoord2f(0.5f, 0.75f);
        stream.vertex2f(3.0f, 4.0f);
        stream.vertex2f(1.0f, 4.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(1, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(GL11.GL_QUADS, batch.opArg(0, 0));
        assertEquals(0, batch.opArg(0, 1));
        assertEquals(4, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.FLAG_TEX | VertexArrayBatch.FLAG_COLOR, batch.opDrawFlags(0));

        assertEquals(4, batch.vertexCount());
        assertEquals(0.25f, batch.texAt(0, 0));
        assertEquals(0.75f, batch.texAt(0, 1));
        assertEquals(10, batch.colorAt(0, 0));
        assertEquals(40, batch.colorAt(0, 3));
        assertEquals(0.5f, batch.texAt(2, 0));
        assertEquals(10, batch.colorAt(2, 0));
        assertEquals(0.0f, batch.posAt(0, 2));
    }

    @Test
    void bindTextureSeparatesDrawOpsInOrder() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.vertex2f(1.0f, 0.0f);
        stream.end();
        stream.bindTexture(1234);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(2.0f, 0.0f);
        stream.vertex2f(3.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(3, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_BIND_TEXTURE, batch.opKind(1));
        assertEquals(1234, batch.opArg(1, 0));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(2));
        assertEquals(2, batch.opArg(2, 1));
        assertEquals(2, batch.opArg(2, 2));
    }

    @Test
    void attributeActivationSplitsOpenSegment() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_TRIANGLE_STRIP);
        stream.vertex2f(0.0f, 0.0f);
        stream.vertex2f(1.0f, 0.0f);
        stream.texCoord2f(0.5f, 0.5f);
        stream.vertex2f(2.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(2, batch.opCount());
        assertEquals(0, batch.opDrawFlags(0));
        assertEquals(0, batch.opArg(0, 1));
        assertEquals(2, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.FLAG_TEX, batch.opDrawFlags(1));
        assertEquals(2, batch.opArg(1, 1));
        assertEquals(1, batch.opArg(1, 2));
        assertEquals(0.5f, batch.texAt(2, 0));
    }

    @Test
    void color3fConvertsToUnsignedBytes() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.color3f(1.0f, 0.5f, 0.0f);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(VertexArrayBatch.FLAG_COLOR, batch.opDrawFlags(0));
        assertEquals(255, batch.colorAt(0, 0));
        assertEquals(128, batch.colorAt(0, 1));
        assertEquals(0, batch.colorAt(0, 2));
        assertEquals(255, batch.colorAt(0, 3));
    }

    @Test
    void emptySegmentsProduceNoDraw() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.end();
        stream.enable(GL11.GL_BLEND);
        stream.begin(GL11.GL_QUADS);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(1, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(GL11.GL_BLEND, batch.opArg(0, 0));
        assertEquals(0, batch.vertexCount());
    }

    @Test
    void stateOpsKeepRecordingOrder() {
        VertexStream stream = new VertexStream();
        stream.enable(GL11.GL_BLEND);
        stream.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();
        stream.disable(GL11.GL_BLEND);

        VertexArrayBatch batch = replay(stream);

        assertEquals(4, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_BLEND_FUNC, batch.opKind(1));
        assertEquals(GL11.GL_SRC_ALPHA, batch.opArg(1, 0));
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, batch.opArg(1, 1));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(3));
    }

    @Test
    void startReplayResetsAccumulatedState() {
        VertexArrayBatch batch = new VertexArrayBatch();
        VertexStream first = new VertexStream();
        first.begin(GL11.GL_QUADS);
        first.color4ub((byte) 1, (byte) 2, (byte) 3, (byte) 4);
        first.vertex2f(0.0f, 0.0f);
        first.end();
        byte[] firstData = new byte[first.length()];
        first.copyTo(firstData);
        VertexStream.replay(firstData, firstData.length, batch);
        assertEquals(1, batch.vertexCount());

        VertexStream second = new VertexStream();
        second.begin(GL11.GL_QUADS);
        second.vertex2f(9.0f, 9.0f);
        second.end();
        byte[] secondData = new byte[second.length()];
        second.copyTo(secondData);
        VertexStream.replay(secondData, secondData.length, batch);

        assertEquals(1, batch.vertexCount());
        assertEquals(1, batch.opCount());
        assertEquals(0, batch.opDrawFlags(0));
        assertEquals(9.0f, batch.posAt(0, 0));
    }

    @Test
    void spriteSequenceCollapsesToOneDrawPerStateRun() {
        VertexStream stream = new VertexStream();
        for (int i = 0; i < 3; i++) {
            stream.color4ub((byte) 255, (byte) 255, (byte) 255, (byte) 255);
            stream.enable(GL11.GL_TEXTURE_2D);
            stream.enable(GL11.GL_BLEND);
            stream.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            stream.begin(GL11.GL_QUADS);
            stream.texCoord2f(0.0f, 0.0f);
            stream.vertex2f(i * 10.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 5.0f);
            stream.vertex2f(i * 10.0f, 5.0f);
            stream.end();
            stream.disable(GL11.GL_BLEND);
        }

        VertexArrayBatch batch = replay(stream);

        assertEquals(7, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(GL11.GL_TEXTURE_2D, batch.opArg(0, 0));
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(1));
        assertEquals(GL11.GL_BLEND, batch.opArg(1, 0));
        assertEquals(VertexArrayBatch.OP_BLEND_FUNC, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(3));
        assertEquals(0, batch.opArg(3, 1));
        assertEquals(12, batch.opArg(3, 2));
        assertEquals(VertexArrayBatch.OP_NOOP, batch.opKind(4));
        assertEquals(VertexArrayBatch.OP_NOOP, batch.opKind(5));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(6));
        assertEquals(GL11.GL_BLEND, batch.opArg(6, 0));
    }

    @Test
    void disableObservedByDrawIsKept() {
        VertexStream stream = new VertexStream();
        stream.enable(GL11.GL_BLEND);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();
        stream.disable(GL11.GL_BLEND);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(1.0f, 1.0f);
        stream.end();
        stream.enable(GL11.GL_BLEND);

        VertexArrayBatch batch = replay(stream);

        assertEquals(5, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
        assertEquals(1, batch.opArg(1, 2));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(3));
        assertEquals(1, batch.opArg(3, 1));
        assertEquals(1, batch.opArg(3, 2));
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(4));
    }

    @Test
    void unobservedBlendFuncIsOverwrittenInPlace() {
        VertexStream stream = new VertexStream();
        stream.blendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        stream.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(2, batch.opCount());
        assertEquals(VertexArrayBatch.OP_BLEND_FUNC, batch.opKind(0));
        assertEquals(GL11.GL_SRC_ALPHA, batch.opArg(0, 0));
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, batch.opArg(0, 1));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
    }

    @Test
    void sameTextureRebindIsSkippedAndDrawsMerge() {
        VertexStream stream = new VertexStream();
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                stream.bindTexture(5);
            }
            stream.begin(GL11.GL_QUADS);
            stream.texCoord2f(0.0f, 0.0f);
            stream.vertex2f(i, 0.0f);
            stream.vertex2f(i + 1.0f, 0.0f);
            stream.vertex2f(i + 1.0f, 1.0f);
            stream.vertex2f(i, 1.0f);
            stream.end();
        }

        VertexArrayBatch batch = replay(stream);

        assertEquals(3, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(4, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.OP_BIND_TEXTURE, batch.opKind(1));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(2));
        assertEquals(4, batch.opArg(2, 1));
        assertEquals(8, batch.opArg(2, 2));
    }

    @Test
    void spriteQuadRoundTrip() {
        VertexStream stream = new VertexStream();
        stream.color4ub((byte) 255, (byte) 0, (byte) 0, (byte) 255);
        stream.spriteQuad(
                10.0f, 20.0f, 10.0f, 30.0f, 20.0f, 30.0f, 20.0f, 20.0f,
                0.25f, 0.5f, 0.125f, 0.25f);

        VertexArrayBatch batch = replay(stream);

        assertEquals(1, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(GL11.GL_QUADS, batch.opArg(0, 0));
        assertEquals(4, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.FLAG_TEX | VertexArrayBatch.FLAG_COLOR, batch.opDrawFlags(0));
        assertEquals(0.25f, batch.texAt(0, 0));
        assertEquals(0.5f, batch.texAt(0, 1));
        assertEquals(0.75f, batch.texAt(1, 1));
        assertEquals(0.375f, batch.texAt(2, 0));
        assertEquals(0.5f, batch.texAt(3, 1));
        assertEquals(20.0f, batch.posAt(3, 0));
        assertEquals(255, batch.colorAt(0, 0));
    }

    @Test
    void mergedRunDeduplicatesAcrossBatchBoundary() {
        VertexArrayBatch batch = new VertexArrayBatch();
        batch.startReplay();
        for (int i = 0; i < 2; i++) {
            VertexStream stream = new VertexStream();
            stream.enable(GL11.GL_BLEND);
            stream.begin(GL11.GL_QUADS);
            stream.vertex2f(i * 10.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 5.0f);
            stream.vertex2f(i * 10.0f, 5.0f);
            stream.end();
            stream.disable(GL11.GL_BLEND);
            byte[] data = new byte[stream.length()];
            stream.copyTo(data);
            VertexStream.replayBody(data, data.length, batch);
        }
        batch.finishReplay();

        assertEquals(4, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
        assertEquals(0, batch.opArg(1, 1));
        assertEquals(8, batch.opArg(1, 2));
        assertEquals(VertexArrayBatch.OP_NOOP, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(3));
        assertEquals(8, batch.vertexCount());
    }

    @Test
    void startReplayResetsSealedBufferLimits() {
        VertexArrayBatch batch = new VertexArrayBatch();
        VertexStream small = new VertexStream();
        small.begin(GL11.GL_QUADS);
        small.vertex2f(0.0f, 0.0f);
        small.end();
        byte[] smallData = new byte[small.length()];
        small.copyTo(smallData);
        VertexStream.replay(smallData, smallData.length, batch);
        batch.sealBuffers();

        VertexStream large = new VertexStream();
        large.begin(GL11.GL_TRIANGLES);
        for (int i = 0; i < 3000; i++) {
            large.vertex3f(i, i + 1, i + 2);
        }
        large.end();
        byte[] largeData = new byte[large.length()];
        large.copyTo(largeData);
        VertexStream.replay(largeData, largeData.length, batch);

        assertEquals(3000, batch.vertexCount());
        assertEquals(2999.0f, batch.posAt(2999, 0));
    }

    @Test
    void vertexCapacityGrowsForLargeBatch() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_TRIANGLES);
        for (int i = 0; i < 5000; i++) {
            stream.vertex3f(i, i + 1, i + 2);
        }
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(5000, batch.vertexCount());
        assertEquals(1, batch.opCount());
        assertEquals(5000, batch.opArg(0, 2));
        assertEquals(4999.0f, batch.posAt(4999, 0));
        assertEquals(5001.0f, batch.posAt(4999, 2));
    }

    @Test
    void oddLineSegmentIsNotMergedAcrossPrimitiveBoundary() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_LINES);
        stream.vertex2f(0.0f, 0.0f);
        stream.vertex2f(1.0f, 0.0f);
        stream.vertex2f(2.0f, 0.0f);
        stream.end();
        stream.begin(GL11.GL_LINES);
        stream.vertex2f(0.0f, 1.0f);
        stream.vertex2f(1.0f, 1.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(2, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(GL11.GL_LINES, batch.opArg(0, 0));
        assertEquals(0, batch.opArg(0, 1));
        assertEquals(3, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
        assertEquals(GL11.GL_LINES, batch.opArg(1, 0));
        assertEquals(3, batch.opArg(1, 1));
        assertEquals(2, batch.opArg(1, 2));
        assertEquals(5, batch.vertexCount());
        assertEquals(0.0f, batch.posAt(0, 0));
        assertEquals(1.0f, batch.posAt(1, 0));
        assertEquals(2.0f, batch.posAt(2, 0));
        assertEquals(0.0f, batch.posAt(3, 0));
        assertEquals(1.0f, batch.posAt(4, 0));
        assertEquals(1.0f, batch.posAt(3, 1));
        assertEquals(1.0f, batch.posAt(4, 1));
    }

    @Test
    void lineStripIsNeverMerged() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_LINE_STRIP);
        stream.vertex2f(0.0f, 0.0f);
        stream.vertex2f(1.0f, 0.0f);
        stream.vertex2f(2.0f, 0.0f);
        stream.end();
        stream.begin(GL11.GL_LINE_STRIP);
        stream.vertex2f(0.0f, 1.0f);
        stream.vertex2f(1.0f, 1.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(2, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(GL11.GL_LINE_STRIP, batch.opArg(0, 0));
        assertEquals(3, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
        assertEquals(GL11.GL_LINE_STRIP, batch.opArg(1, 0));
        assertEquals(3, batch.opArg(1, 1));
        assertEquals(2, batch.opArg(1, 2));
    }

    @Test
    void alignedLineSegmentsStillMerge() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_LINES);
        stream.vertex2f(0.0f, 0.0f);
        stream.vertex2f(1.0f, 0.0f);
        stream.vertex2f(2.0f, 0.0f);
        stream.vertex2f(3.0f, 0.0f);
        stream.end();
        stream.begin(GL11.GL_LINES);
        stream.vertex2f(0.0f, 1.0f);
        stream.vertex2f(1.0f, 1.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(1, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(GL11.GL_LINES, batch.opArg(0, 0));
        assertEquals(0, batch.opArg(0, 1));
        assertEquals(6, batch.opArg(0, 2));
    }
}
