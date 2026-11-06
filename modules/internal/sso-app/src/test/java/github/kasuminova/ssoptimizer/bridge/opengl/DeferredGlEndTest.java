package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeferredGlEndTest {

    private FakeRenderQueue queue;
    private boolean savedEnabled;
    private int savedThreshold;

    @BeforeEach
    void setUp() {
        savedEnabled = BridgeSupport.deferredGlEndEnabled;
        savedThreshold = BridgeSupport.deferredGlEndThresholdBytes;
        queue = new FakeRenderQueue();
        GL11.install(queue);
        BridgeSupport.deferredGlEndEnabled = true;
        BridgeSupport.deferredGlEndThresholdBytes = 1 << 20;
    }

    @AfterEach
    void tearDown() {
        BridgeSupport.deferredGlEndEnabled = savedEnabled;
        BridgeSupport.deferredGlEndThresholdBytes = savedThreshold;
        GL11.uninstall();
    }

    private static void recordSegment(float base) {
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glTexCoord2f(base, 0f);
        GL11.glVertex2f(base, 0f);
        GL11.glTexCoord2f(base, 1f);
        GL11.glVertex2f(base, 1f);
        GL11.glEnd();
    }

    private static final class RecordingSink implements VertexSink {
        final List<String> events = new ArrayList<>();

        @Override
        public void begin(int mode) {
            events.add("begin:" + mode);
        }

        @Override
        public void end() {
            events.add("end");
        }

        @Override
        public void vertex2f(float x, float y) {
            events.add("vertex2f:" + x + "," + y);
        }

        @Override
        public void vertex3f(float x, float y, float z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void vertex2d(double x, double y) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void vertex3d(double x, double y, double z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void texCoord2f(float s, float t) {
            events.add("texCoord2f:" + s + "," + t);
        }

        @Override
        public void texCoord2d(double s, double t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void color4ub(byte red, byte green, byte blue, byte alpha) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void color3ub(byte red, byte green, byte blue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void color3f(float red, float green, float blue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void color4f(float red, float green, float blue, float alpha) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void color3d(double red, double green, double blue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void normal3f(float nx, float ny, float nz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void enable(int cap) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disable(int cap) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void blendFunc(int src, int dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bindTexture(int texture) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void multipleSegmentsAccumulateIntoOneBatchWithEquivalentByteStream() {
        recordSegment(0f);
        recordSegment(1f);
        assertEquals(0, queue.recorded.size(), "glEnd 不再立即落帧");
        GL11.glFlush();
        assertEquals(2, queue.recorded.size(), "累积流段一次落帧 + glFlush 命令");
        VertexBatchCommand deferredBatch = (VertexBatchCommand) queue.recorded.get(0);
        assertFalse(deferredBatch.immediate(), "多段闭合段累积的批次非开放段切割");
        RecordingSink deferredSink = new RecordingSink();
        VertexStream.replayBody(deferredBatch.data(), deferredBatch.length(), deferredSink);

        GL11.uninstall();
        FakeRenderQueue legacyQueue = new FakeRenderQueue();
        GL11.install(legacyQueue);
        BridgeSupport.deferredGlEndEnabled = false;
        recordSegment(0f);
        recordSegment(1f);
        assertEquals(2, legacyQueue.recorded.size(), "旧模式每段 glEnd 各落帧一条批次");
        RecordingSink legacySink = new RecordingSink();
        for (int i = 0; i < 2; i++) {
            VertexBatchCommand batch = (VertexBatchCommand) legacyQueue.recorded.get(i);
            VertexStream.replayBody(batch.data(), batch.length(), legacySink);
        }

        assertEquals(legacySink.events, deferredSink.events,
                "累积一次落帧与逐段落帧拼接的回放事件序列必须逐条等价");
        assertEquals(12, deferredSink.events.size(), "两段各 6 个事件（begin+2×(tex+vertex)+end）");
    }

    @Test
    void nonStreamCommandFlushesPendingStreamBeforeItself() {
        recordSegment(0f);
        GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        assertEquals(2, queue.recorded.size(), "流段批次先于状态命令落帧");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0),
                "帧列表首条必须是顶点流批次（流段录制在 enable 之前）");
        RecordingSink sink = new RecordingSink();
        VertexBatchCommand batch = (VertexBatchCommand) queue.recorded.get(0);
        VertexStream.replayBody(batch.data(), batch.length(), sink);
        assertEquals(List.of(
                "begin:" + org.lwjgl.opengl.GL11.GL_QUADS,
                "texCoord2f:0.0,0.0", "vertex2f:0.0,0.0",
                "texCoord2f:0.0,1.0", "vertex2f:0.0,1.0",
                "end"), sink.events);
    }

    @Test
    void capacityThresholdForcesFlushAtGlEnd() {
        BridgeSupport.deferredGlEndThresholdBytes = 8;
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        assertEquals(0, queue.recorded.size(), "段中途不落帧");
        GL11.glEnd();
        assertEquals(1, queue.recorded.size(), "容量阈值触线的 glEnd 必须强制落帧");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0));
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glVertex2f(1f, 1f);
        GL11.glEnd();
        assertEquals(2, queue.recorded.size(), "触线落帧后下一段达到阈值再次落帧");
    }

    @Test
    void displayUpdateFlushesPendingStreamBeforeSwapCommand() {
        recordSegment(0f);
        Display.update();
        assertEquals(2, queue.recorded.size(), "挂起流段批次必须先于 Display.update 命令入帧");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0));
        assertEquals(1, queue.swapAndSyncCount);
    }

    @Test
    void swapCutsPendingStreamIntoCurrentFrame() {
        recordSegment(0f);
        assertEquals(0, queue.recorded.size());
        BridgeSupport.swapFrames();
        assertEquals(1, queue.recorded.size(), "swap 前挂起流段必须切进当前帧");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0));
        assertEquals(1, queue.swapCount, "落帧先于帧切割");

        recordSegment(2f);
        BridgeSupport.swapFramesAndSync();
        assertEquals(2, queue.recorded.size(), "swapAndSync 同样先落帧再切割");
        assertEquals(1, queue.swapAndSyncCount);
    }

    @Test
    void pendingStreamStateOpsBreakDedupAdjacency() {
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.streamBindTexture(7);
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        assertEquals(3, queue.recorded.size(),
                "挂起流内状态指令必须打断相邻性：bind + 流批次 + bind 重新入队");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(1));
    }

    @Test
    void pureVertexPendingStreamKeepsDedupAdjacency() {
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        recordSegment(0f);
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        assertEquals(1, queue.recorded.size(), "纯顶点挂起流不打断相邻性，重复 bind 被去重");
        GL11.glFlush();
        assertEquals(3, queue.recorded.size());
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(1));
    }

    @Test
    void disabledSwitchRestoresImmediateFlush() {
        BridgeSupport.deferredGlEndEnabled = false;
        recordSegment(0f);
        assertEquals(1, queue.recorded.size(), "开关关闭时 glEnd 立即落帧（旧行为）");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0));
    }

    @Test
    void flushDecisionCoversSwitchAuxAndThreshold() {
        RecordingContext ctx = BridgeSupport.recordingContext();
        recordSegment(0f);
        assertFalse(BridgeSupport.shouldFlushOnGlEnd(ctx),
                "默认（开关开、非 aux、未触阈值）：glEnd 不落帧");

        BridgeSupport.deferredGlEndEnabled = false;
        assertTrue(BridgeSupport.shouldFlushOnGlEnd(ctx), "开关关闭：回退立即落帧");
        BridgeSupport.deferredGlEndEnabled = true;

        ctx.auxNative = true;
        try {
            assertTrue(BridgeSupport.shouldFlushOnGlEnd(ctx),
                    "aux 原生线程：阻塞通道内联直执不经 flush，glEnd 必须立即落帧");
        } finally {
            ctx.auxNative = false;
        }

        BridgeSupport.deferredGlEndThresholdBytes = 1;
        assertTrue(BridgeSupport.shouldFlushOnGlEnd(ctx), "容量阈值触线：强制落帧");
    }
}
