package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamMatrixOpsTest {

    private FakeRenderQueue queue;
    private boolean savedStreamMatrixOps;

    @BeforeEach
    void setUp() {
        savedStreamMatrixOps = BridgeSupport.streamMatrixOpsEnabled;
        queue = new FakeRenderQueue();
        GL11.install(queue);
        BridgeSupport.streamMatrixOpsEnabled = true;
    }

    @AfterEach
    void tearDown() {
        BridgeSupport.streamMatrixOpsEnabled = savedStreamMatrixOps;
        GL11.uninstall();
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
            events.add("enable:" + cap);
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

        @Override
        public void pushMatrix() {
            events.add("pushMatrix");
        }

        @Override
        public void popMatrix() {
            events.add("popMatrix");
        }

        @Override
        public void loadIdentity() {
            events.add("loadIdentity");
        }

        @Override
        public void translatef(float x, float y, float z) {
            events.add("translatef:" + x + "," + y + "," + z);
        }

        @Override
        public void rotatef(float angle, float x, float y, float z) {
            events.add("rotatef:" + angle + "," + x + "," + y + "," + z);
        }

        @Override
        public void scalef(float x, float y, float z) {
            events.add("scalef:" + x + "," + y + "," + z);
        }

        @Override
        public void matrixMode(int mode) {
            events.add("matrixMode:" + mode);
        }
    }

    private static void recordSegment(float base) {
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glTexCoord2f(base, 0f);
        GL11.glVertex2f(base, 0f);
        GL11.glTexCoord2f(base, 1f);
        GL11.glVertex2f(base, 1f);
        GL11.glEnd();
    }

    @Test
    void matrixOpsEncodeIntoStreamAndRoundtripExactly() {
        GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glPushMatrix();
        GL11.glTranslatef(1.5f, -2.25f, 0.75f);
        GL11.glRotatef(45.5f, 0f, 0f, 1f);
        GL11.glScalef(2f, 0.5f, 1f);
        GL11.glPopMatrix();
        assertEquals(0, queue.recorded.size(), "矩阵调用全部进流，不产生非流式命令");

        GL11.glFlush();
        assertEquals(2, queue.recorded.size(), "流落帧批次 + glFlush 命令");
        VertexBatchCommand batch = assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0));
        assertFalse(batch.immediate(), "纯矩阵指令的批次非开放段切割");

        RecordingSink sink = new RecordingSink();
        VertexStream.replayBody(batch.data(), batch.length(), sink);
        assertEquals(List.of(
                "matrixMode:" + org.lwjgl.opengl.GL11.GL_MODELVIEW,
                "loadIdentity",
                "pushMatrix",
                "translatef:1.5,-2.25,0.75",
                "rotatef:45.5,0.0,0.0,1.0",
                "scalef:2.0,0.5,1.0",
                "popMatrix"), sink.events, "矩阵指令编码→回放往返必须逐指令等价（参数位模式精确）");
    }

    @Test
    void pushSegmentPopKeepsOrderWithExternalCommands() {
        GL11.glPushMatrix();
        recordSegment(3f);
        GL11.glPopMatrix();
        GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        assertEquals(2, queue.recorded.size(), "流批次先于 enable 命令落帧");
        VertexBatchCommand batch = assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0));

        RecordingSink sink = new RecordingSink();
        VertexStream.replayBody(batch.data(), batch.length(), sink);
        assertEquals(List.of(
                "pushMatrix",
                "begin:" + org.lwjgl.opengl.GL11.GL_QUADS,
                "texCoord2f:3.0,0.0", "vertex2f:3.0,0.0",
                "texCoord2f:3.0,1.0", "vertex2f:3.0,1.0",
                "end",
                "popMatrix"), sink.events);
    }

    @Test
    void nonStreamedMatrixCallsFlushPendingStreamInOrder() {
        GL11.glPushMatrix();
        GL11.glTranslatef(1f, 2f, 0f);
        GL11.glOrtho(0, 800, 600, 0, -1, 1);
        assertEquals(2, queue.recorded.size(), "流批次先于 glOrtho 命令落帧");
        VertexBatchCommand batch = assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0));
        RecordingSink sink = new RecordingSink();
        VertexStream.replayBody(batch.data(), batch.length(), sink);
        assertEquals(List.of("pushMatrix", "translatef:1.0,2.0,0.0"), sink.events);
    }

    @Test
    void matrixOpsBreakDrawMergeInArrayBatch() {
        VertexStream stream = new VertexStream();
        stream.begin(org.lwjgl.opengl.GL11.GL_QUADS);
        stream.vertex2f(0f, 0f);
        stream.vertex2f(1f, 0f);
        stream.vertex2f(1f, 1f);
        stream.vertex2f(0f, 1f);
        stream.end();
        stream.pushMatrix();
        stream.translatef(5f, 6f, 0f);
        stream.begin(org.lwjgl.opengl.GL11.GL_QUADS);
        stream.vertex2f(2f, 0f);
        stream.vertex2f(3f, 0f);
        stream.vertex2f(3f, 1f);
        stream.vertex2f(2f, 1f);
        stream.end();
        stream.popMatrix();

        VertexArrayBatch batch = new VertexArrayBatch();
        byte[] data = new byte[stream.length()];
        stream.copyTo(data);
        VertexStream.replay(data, data.length, batch);

        assertEquals(5, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(4, batch.opArg(0, 2), "第一段 4 顶点");
        assertEquals(VertexArrayBatch.OP_PUSH_MATRIX, batch.opKind(1));
        assertEquals(VertexArrayBatch.OP_TRANSLATE_F, batch.opKind(2));
        assertEquals(Float.floatToRawIntBits(5f), batch.opArg(2, 0));
        assertEquals(Float.floatToRawIntBits(6f), batch.opArg(2, 1));
        assertEquals(Float.floatToRawIntBits(0f), batch.opArg(2, 2));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(3));
        assertEquals(4, batch.opArg(3, 1), "第二段 DRAW 的 first 指向顶点 4（未被合并进第一段）");
        assertEquals(4, batch.opArg(3, 2));
        assertEquals(VertexArrayBatch.OP_POP_MATRIX, batch.opKind(4));
    }

    @Test
    void matrixOpsKeepOrderAcrossMergedBatchBoundary() {
        VertexArrayBatch batch = new VertexArrayBatch();
        batch.startReplay();

        VertexStream first = new VertexStream();
        first.begin(org.lwjgl.opengl.GL11.GL_QUADS);
        first.vertex2f(0f, 0f);
        first.vertex2f(1f, 0f);
        first.vertex2f(1f, 1f);
        first.vertex2f(0f, 1f);
        first.end();
        byte[] firstData = new byte[first.length()];
        first.copyTo(firstData);
        VertexStream.replayBody(firstData, firstData.length, batch);

        VertexStream second = new VertexStream();
        second.pushMatrix();
        second.scalef(2f, 2f, 1f);
        second.begin(org.lwjgl.opengl.GL11.GL_QUADS);
        second.vertex2f(2f, 0f);
        second.vertex2f(3f, 0f);
        second.vertex2f(3f, 1f);
        second.vertex2f(2f, 1f);
        second.end();
        second.popMatrix();
        byte[] secondData = new byte[second.length()];
        second.copyTo(secondData);
        VertexStream.replayBody(secondData, secondData.length, batch);
        batch.finishReplay();

        assertEquals(5, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_PUSH_MATRIX, batch.opKind(1));
        assertEquals(VertexArrayBatch.OP_SCALE_F, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(3));
        assertEquals(4, batch.opArg(3, 1));
        assertEquals(VertexArrayBatch.OP_POP_MATRIX, batch.opKind(4));
    }

    @Test
    void pendingMatrixOpsBreakStateDedupAdjacency() {
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.glPushMatrix();
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        assertEquals(3, queue.recorded.size(),
                "挂起流内矩阵指令必须打断相邻性：bind + 流批次 + bind 重新入队");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(1));
    }

    @Test
    void disabledSwitchRestoresEnqueuePerCall() {
        BridgeSupport.streamMatrixOpsEnabled = false;
        GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glPushMatrix();
        GL11.glTranslatef(1f, 2f, 0f);
        GL11.glRotatef(90f, 0f, 0f, 1f);
        GL11.glScalef(2f, 2f, 1f);
        GL11.glPopMatrix();
        assertEquals(7, queue.recorded.size(), "开关关闭时矩阵调用逐条入队（旧行为）");
        assertTrue(BridgeSupport.recordingContext().vertexStream.isEmpty(), "回退路径不触碰顶点流");
    }

    @Test
    void disabledSwitchKeepsMatrixModeDedup() {
        BridgeSupport.streamMatrixOpsEnabled = false;
        GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        assertEquals(2, queue.recorded.size(), "回退路径保留 glMatrixMode 的连续相同去重");
    }

    @Test
    void decisionCoversSwitchAndAuxNative() {
        RecordingContext ctx = BridgeSupport.recordingContext();
        assertTrue(BridgeSupport.shouldStreamMatrixOps(ctx), "默认（开关开、非 aux）：矩阵指令进流");

        BridgeSupport.streamMatrixOpsEnabled = false;
        assertFalse(BridgeSupport.shouldStreamMatrixOps(ctx), "开关关闭：回退 enqueue");
        BridgeSupport.streamMatrixOpsEnabled = true;

        ctx.auxNative = true;
        try {
            assertFalse(BridgeSupport.shouldStreamMatrixOps(ctx),
                    "aux 原生线程：阻塞通道内联直执不经 flush，矩阵指令必须走 enqueue 立即执行");
        } finally {
            ctx.auxNative = false;
        }
    }
}
