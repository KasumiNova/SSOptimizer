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
 * {@link TextStreamEmitter#emit(List, int, int)}（图集路径）的编码验证：
 * pass 内按 textureId 连续分组换绑切段，同 id 连续 quad 不切断；
 * 假 RenderQueue 截获落帧批次回放到记录桩断言（无 GL 上下文）。
 */
class TextStreamEmitterAtlasTest {

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
            calls.add("v3");
        }

        @Override
        public void vertex2d(double x, double y) {
            calls.add("vd");
        }

        @Override
        public void vertex3d(double x, double y, double z) {
            calls.add("vd3");
        }

        @Override
        public void texCoord2f(float s, float t) {
            calls.add("t:" + s + "," + t);
        }

        @Override
        public void texCoord2d(double s, double t) {
            calls.add("td");
        }

        @Override
        public void color4ub(byte red, byte green, byte blue, byte alpha) {
            calls.add("c:" + red + "," + green + "," + blue + "," + alpha);
        }

        @Override
        public void color3ub(byte red, byte green, byte blue) {
            calls.add("c3");
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

    private static GlyphQuad quad(final float x, final int argb, final int textureId) {
        return new GlyphQuad(
                x, 0f, 0f, 1f,
                x, 10f, 0f, 0f,
                x + 8f, 10f, 1f, 0f,
                x + 8f, 0f, 1f, 1f,
                argb,
                textureId);
    }

    private List<String> replayRecordedBatches() {
        final RecordingSink sink = new RecordingSink();
        for (final Object command : queue.recorded) {
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
    void groupsByTextureIdWithBindBetweenSegments() {
        // 纹理序列 5,5,7,5：三段（5 连续合并、7 单独、回到 5 再切）
        final List<TextPass> passes = List.of(new TextPass(List.of(
                quad(0f, 0xFFFFFFFF, 5),
                quad(10f, 0xFFFFFFFF, 5),
                quad(20f, 0xFFFFFFFF, 7),
                quad(30f, 0xFFFFFFFF, 5))));
        TextStreamEmitter.emit(passes, 770, 771);
        flushTail();

        final List<String> calls = replayRecordedBatches();
        final List<String> structure = calls.stream()
                .filter(c -> c.startsWith("bind:") || c.startsWith("begin") || c.equals("end"))
                .toList();
        assertEquals(List.of(
                "bind:5", "begin:7", "end",
                "bind:7", "begin:7", "end",
                "bind:5", "begin:7", "end"), structure,
                "textureId 变化处切段换绑，同 id 连续 quad 不切断");
        assertEquals("enable:3553", calls.get(0), "图集路径同样启用 TEXTURE_2D");
        assertEquals("disable:3042", calls.get(calls.size() - 1));
    }

    @Test
    void singleTexturePassDoesNotRebind() {
        final List<TextPass> passes = List.of(new TextPass(List.of(
                quad(0f, 0xFFFFFFFF, 5),
                quad(10f, 0xFFFFFFFF, 5))));
        TextStreamEmitter.emit(passes, 770, 771);
        flushTail();

        final List<String> calls = replayRecordedBatches();
        assertEquals(1, calls.stream().filter(c -> c.startsWith("bind:")).count());
        assertEquals(1, calls.stream().filter(c -> c.startsWith("begin")).count());
    }

    @Test
    void colorSwitchSurvivesAcrossTextureSegments() {
        // 换纹理边界处颜色同时变化：颜色指令照常发（颜色状态与纹理分段正交）
        final List<TextPass> passes = List.of(new TextPass(List.of(
                quad(0f, 0xFFFF0000, 5),
                quad(10f, 0xFFFFFFFF, 7))));
        TextStreamEmitter.emit(passes, 770, 771);
        flushTail();

        final List<String> calls = replayRecordedBatches();
        assertEquals(2, calls.stream().filter(c -> c.startsWith("c:")).count());
        // 颜色切换发生在第二段的 begin 之后
        final int secondBegin = calls.lastIndexOf("begin:7");
        final int secondColor = calls.lastIndexOf("c:-1,-1,-1,-1");
        assertTrue(secondColor > secondBegin, "颜色指令在新纹理段内发射");
    }

    @Test
    void emptyPassesProduceNoSegments() {
        final List<TextPass> passes = List.of(
                new TextPass(List.of()),
                new TextPass(List.of(quad(0f, 0xFFFFFFFF, 5))),
                new TextPass(List.of()));
        TextStreamEmitter.emit(passes, 770, 771);
        flushTail();

        final List<String> calls = replayRecordedBatches();
        assertEquals(1, calls.stream().filter(c -> c.startsWith("begin")).count(), "空 pass 不产流段");
    }
}
