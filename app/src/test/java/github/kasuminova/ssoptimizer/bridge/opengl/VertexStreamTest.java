package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VertexStream} 的编解码往返验证：录制侧编码的 immediate 调用序列，
 * 经 {@link VertexStream#copyTo(byte[])} 取出后回放必须逐指令等价
 * （含 double 精度与 color4ub 的负字节值）。
 */
class VertexStreamTest {

    /** 把流的当前内容拷出并回放到 sink（生产路径是 VertexBatchCommand 池化命令，见 BridgeSupport）。 */
    private static void replayInto(VertexStream stream, VertexSink sink) {
        byte[] data = new byte[stream.length()];
        stream.copyTo(data);
        VertexStream.replay(data, data.length, sink);
    }

    /** 记录桩：把回放调用序列化成字符串列表，便于精确断言顺序与参数值。 */
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
            calls.add("vertex2f:" + x + "," + y);
        }

        @Override
        public void vertex3f(float x, float y, float z) {
            calls.add("vertex3f:" + x + "," + y + "," + z);
        }

        @Override
        public void vertex2d(double x, double y) {
            calls.add("vertex2d:" + x + "," + y);
        }

        @Override
        public void vertex3d(double x, double y, double z) {
            calls.add("vertex3d:" + x + "," + y + "," + z);
        }

        @Override
        public void texCoord2f(float s, float t) {
            calls.add("texCoord2f:" + s + "," + t);
        }

        @Override
        public void texCoord2d(double s, double t) {
            calls.add("texCoord2d:" + s + "," + t);
        }

        @Override
        public void color4ub(byte red, byte green, byte blue, byte alpha) {
            calls.add("color4ub:" + red + "," + green + "," + blue + "," + alpha);
        }

        @Override
        public void color3ub(byte red, byte green, byte blue) {
            calls.add("color3ub:" + red + "," + green + "," + blue);
        }

        @Override
        public void color3f(float red, float green, float blue) {
            calls.add("color3f:" + red + "," + green + "," + blue);
        }

        @Override
        public void color4f(float red, float green, float blue, float alpha) {
            calls.add("color4f:" + red + "," + green + "," + blue + "," + alpha);
        }

        @Override
        public void color3d(double red, double green, double blue) {
            calls.add("color3d:" + red + "," + green + "," + blue);
        }

        @Override
        public void normal3f(float nx, float ny, float nz) {
            calls.add("normal3f:" + nx + "," + ny + "," + nz);
        }
    }

    @Test
    void allOpsRoundtripExactly() {
        VertexStream stream = new VertexStream();
        stream.begin(org.lwjgl.opengl.GL11.GL_QUADS);
        stream.texCoord2f(0.25f, 0.75f);
        stream.color4ub((byte) 255, (byte) 128, (byte) -1, (byte) 0);
        stream.color3ub((byte) -128, (byte) 1, (byte) 127);
        stream.color4f(1f, 0.5f, 0.25f, 0.125f);
        stream.color3f(0.1f, 0.2f, 0.3f);
        stream.color3d(0.123456789, -0.987654321, 1e300);
        stream.normal3f(0f, 0f, 1f);
        stream.vertex2f(3.5f, -4.25f);
        stream.vertex3f(1f, 2f, 3f);
        stream.vertex2d(123456.789, -987654.321);
        stream.vertex3d(1e-300, 1.7e308, -3.5);
        stream.texCoord2d(0.3333333333333333, 0.6666666666666666);
        stream.end();

        RecordingSink sink = new RecordingSink();
        replayInto(stream, sink);

        assertEquals(List.of(
                "begin:" + org.lwjgl.opengl.GL11.GL_QUADS,
                "texCoord2f:0.25,0.75",
                "color4ub:-1,-128,-1,0",
                "color3ub:-128,1,127",
                "color4f:1.0,0.5,0.25,0.125",
                "color3f:0.1,0.2,0.3",
                "color3d:0.123456789,-0.987654321,1.0E300",
                "normal3f:0.0,0.0,1.0",
                "vertex2f:3.5,-4.25",
                "vertex3f:1.0,2.0,3.0",
                "vertex2d:123456.789,-987654.321",
                "vertex3d:1.0E-300,1.7E308,-3.5",
                "texCoord2d:0.3333333333333333,0.6666666666666666",
                "end"), sink.calls);
    }

    @Test
    void growthAcrossCapacityPreservesAllOps() {
        VertexStream stream = new VertexStream();
        int count = 10000; // 9 字节/顶点，远超 4096 初始容量，强制多次扩容
        stream.begin(org.lwjgl.opengl.GL11.GL_QUADS);
        for (int i = 0; i < count; i++) {
            stream.vertex2f(i, -i);
        }
        stream.end();

        RecordingSink sink = new RecordingSink();
        replayInto(stream, sink);
        assertEquals(count + 2, sink.calls.size());
        assertEquals("begin:" + org.lwjgl.opengl.GL11.GL_QUADS, sink.calls.get(0));
        assertEquals("vertex2f:0.0,0.0", sink.calls.get(1));
        assertEquals("vertex2f:" + (float) (count - 1) + "," + (float) -(count - 1), sink.calls.get(count));
        assertEquals("end", sink.calls.get(count + 1));
    }

    @Test
    void copyOutThenResetKeepsCopiedContentIntact() {
        VertexStream stream = new VertexStream();
        stream.begin(org.lwjgl.opengl.GL11.GL_TRIANGLES);
        stream.vertex2f(1f, 1f);
        stream.end();
        byte[] firstCopy = new byte[stream.length()];
        stream.copyTo(firstCopy);
        stream.reset();
        assertTrue(stream.isEmpty(), "reset 后流必须为空");
        assertEquals(0, stream.length());

        // 第一批拷出后继续录第二批，再回放第一批拷贝：内容不受后续录制影响
        stream.begin(org.lwjgl.opengl.GL11.GL_LINES);
        stream.vertex2f(9f, 9f);
        stream.end();

        RecordingSink firstSink = new RecordingSink();
        VertexStream.replay(firstCopy, firstCopy.length, firstSink);
        RecordingSink secondSink = new RecordingSink();
        replayInto(stream, secondSink);
        assertEquals(List.of("begin:" + org.lwjgl.opengl.GL11.GL_TRIANGLES, "vertex2f:1.0,1.0", "end"),
                firstSink.calls);
        assertEquals(List.of("begin:" + org.lwjgl.opengl.GL11.GL_LINES, "vertex2f:9.0,9.0", "end"),
                secondSink.calls);
    }
}
