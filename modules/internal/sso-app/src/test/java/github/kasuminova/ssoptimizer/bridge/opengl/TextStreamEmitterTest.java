package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.font.emit.TextStreamEmitter;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TextStreamEmitter} 经 bridge 顶点流的编码验证：假 RenderQueue 截获落帧的
 * 池化批次命令，回放字节流到记录桩，断言与预期 GL 调用序列逐条一致（无 GL 上下文）。
 */
class TextStreamEmitterTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL11.install(queue);
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
    }

    /** 记录桩：回放调用序列化为字符串，精确断言顺序与参数。 */
    private static final class RecordingSink implements VertexSink {
        final List<String> calls = new ArrayList<>();

        @Override
        public void begin(int mode) {
            calls.add("begin:" + mode);
        }

        @Override
        public void end() {
            calls.add("end");
        }

        @Override
        public void vertex2f(float x, float y) {
            calls.add("v:" + x + "," + y);
        }

        @Override
        public void vertex3f(float x, float y, float z) {
            calls.add("v3:" + x + "," + y + "," + z);
        }

        @Override
        public void vertex2d(double x, double y) {
            calls.add("vd:" + x + "," + y);
        }

        @Override
        public void vertex3d(double x, double y, double z) {
            calls.add("vd3:" + x + "," + y + "," + z);
        }

        @Override
        public void texCoord2f(float s, float t) {
            calls.add("t:" + s + "," + t);
        }

        @Override
        public void texCoord2d(double s, double t) {
            calls.add("td:" + s + "," + t);
        }

        @Override
        public void color4ub(byte red, byte green, byte blue, byte alpha) {
            calls.add("c:" + red + "," + green + "," + blue + "," + alpha);
        }

        @Override
        public void color3ub(byte red, byte green, byte blue) {
            calls.add("c3:" + red + "," + green + "," + blue);
        }

        @Override
        public void color3f(float red, float green, float blue) {
            calls.add("c3f");
        }

        @Override
        public void color4f(float red, float green, float blue, float alpha) {
            calls.add("c4f");
        }

        @Override
        public void color3d(double red, double green, double blue) {
            calls.add("c3d");
        }

        @Override
        public void normal3f(float nx, float ny, float nz) {
            calls.add("n");
        }

        @Override
        public void enable(int cap) {
            calls.add("enable:" + cap);
        }

        @Override
        public void disable(int cap) {
            calls.add("disable:" + cap);
        }

        @Override
        public void blendFunc(int src, int dst) {
            calls.add("blend:" + src + "," + dst);
        }

        @Override
        public void bindTexture(int texture) {
            calls.add("bind:" + texture);
        }
    }

    private static GlyphQuad quad(float x, int argb) {
        return new GlyphQuad(
                x, 0f, 0f, 1f,
                x, 10f, 0f, 0f,
                x + 8f, 10f, 1f, 0f,
                x + 8f, 0f, 1f, 1f,
                argb);
    }

    /** 取出所有已落帧的顶点批次并顺序回放到一个 sink。 */
    private List<String> replayRecordedBatches() {
        RecordingSink sink = new RecordingSink();
        for (Object command : queue.recorded) {
            if (command instanceof VertexBatchCommand batch) {
                VertexStream.replay(batch.data(), batch.length(), sink);
            }
        }
        return sink.calls;
    }

    /** 末尾的 streamDisable 滞留在线程流缓冲，用一条非流式命令触发落帧。 */
    private void flushTail() {
        GL11.glFlush();
    }

    @Test
    void emitsStateSetupPassesAndTeardownInOrder() {
        List<TextPass> passes = List.of(
                new TextPass(List.of(quad(0f, 0xFFFF0000), quad(10f, 0xFFFFFFFF))),
                new TextPass(List.of(quad(20f, 0xFFFFFFFF))));
        TextStreamEmitter.emitPasses(passes, 770, 771);
        flushTail();

        List<String> calls = replayRecordedBatches();
        List<String> expected = List.of(
                // 段1：blend 状态 + pass1（红→白变色）
                "enable:3042", "blend:770,771",
                "begin:7",
                "c:-1,0,0,-1",
                "t:0.0,1.0", "v:0.0,0.0", "t:0.0,0.0", "v:0.0,10.0",
                "t:1.0,0.0", "v:8.0,10.0", "t:1.0,1.0", "v:8.0,0.0",
                "c:-1,-1,-1,-1",
                "t:0.0,1.0", "v:10.0,0.0", "t:0.0,0.0", "v:10.0,10.0",
                "t:1.0,0.0", "v:18.0,10.0", "t:1.0,1.0", "v:18.0,0.0",
                "end",
                // 段2：pass2（同色延续，去重不再发 color）
                "begin:7",
                "t:0.0,1.0", "v:20.0,0.0", "t:0.0,0.0", "v:20.0,10.0",
                "t:1.0,0.0", "v:28.0,10.0", "t:1.0,1.0", "v:28.0,0.0",
                "end",
                // 尾段：关 BLEND
                "disable:3042");
        assertEquals(expected, calls);
    }

    @Test
    void emptyPassesAreSkipped() {
        List<TextPass> passes = List.of(
                new TextPass(List.of()),
                new TextPass(List.of(quad(0f, 0xFFFFFFFF))),
                new TextPass(List.of()));
        TextStreamEmitter.emitPasses(passes, 770, 771);
        flushTail();

        List<String> calls = replayRecordedBatches();
        long begins = calls.stream().filter(c -> c.equals("begin:7")).count();
        assertEquals(1, begins, "空 pass 不产流段");
        assertEquals("disable:3042", calls.get(calls.size() - 1));
    }

    @Test
    void colorChangesOnlyEmittedOnTransition() {
        // 同色三连 quad + 一次变色：颜色指令只出现两次
        List<TextPass> passes = List.of(new TextPass(List.of(
                quad(0f, 0xFF00FF00), quad(10f, 0xFF00FF00), quad(20f, 0xFF00FF00),
                quad(30f, 0xFF0000FF))));
        TextStreamEmitter.emitPasses(passes, 770, 771);
        flushTail();

        List<String> calls = replayRecordedBatches();
        long colorCalls = calls.stream().filter(c -> c.startsWith("c:")).count();
        assertEquals(2, colorCalls);
    }
}
