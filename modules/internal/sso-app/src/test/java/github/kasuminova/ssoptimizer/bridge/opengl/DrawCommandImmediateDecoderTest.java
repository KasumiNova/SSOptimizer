package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DrawCommandImmediateDecoder} 的解码逻辑验证：display list 编译窗口内
 * 客户端数组快照 → immediate 调用（按值捕获）的逐顶点重放，以及「不可解码」
 * 快照（VBO 偏移 / INTERLEAVED）的排除判定。
 * <p>
 * 背景：display list 编译对客户端数组按指针捕获，{@link DrawCommand} 的 buffer
 * 形式快照是池化缓冲（draw 执行后归还复用），列表重放会读到陈旧内容——解码器
 * 把快照数据以 glBegin/glVertex 等 immediate 调用重放，列表在编译期按值捕获。
 * 解码器不触碰真实 GL（输出经 {@link VertexSink} 抽象），可无上下文完整验证。
 */
class DrawCommandImmediateDecoderTest {

    /** 记录桩：与 {@link VertexStreamTest} 同构，序列化回放调用便于精确断言。 */
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
            calls.add("vertex2f:" + trim(x) + "," + trim(y));
        }

        @Override
        public void vertex3f(float x, float y, float z) {
            calls.add("vertex3f:" + trim(x) + "," + trim(y) + "," + trim(z));
        }

        @Override
        public void vertex2d(double x, double y) {
            calls.add("vertex2d:" + trim(x) + "," + trim(y));
        }

        @Override
        public void vertex3d(double x, double y, double z) {
            calls.add("vertex3d:" + trim(x) + "," + trim(y) + "," + trim(z));
        }

        @Override
        public void texCoord2f(float s, float t) {
            calls.add("texCoord2f:" + trim(s) + "," + trim(t));
        }

        @Override
        public void texCoord2d(double s, double t) {
            calls.add("texCoord2d:" + trim(s) + "," + trim(t));
        }

        @Override
        public void color4ub(byte red, byte green, byte blue, byte alpha) {
            calls.add("color4ub:" + (red & 0xFF) + "," + (green & 0xFF) + "," + (blue & 0xFF) + "," + (alpha & 0xFF));
        }

        @Override
        public void color3ub(byte red, byte green, byte blue) {
            calls.add("color3ub:" + (red & 0xFF) + "," + (green & 0xFF) + "," + (blue & 0xFF));
        }

        @Override
        public void color3f(float red, float green, float blue) {
            calls.add("color3f:" + trim(red) + "," + trim(green) + "," + trim(blue));
        }

        @Override
        public void color4f(float red, float green, float blue, float alpha) {
            calls.add("color4f:" + trim(red) + "," + trim(green) + "," + trim(blue) + "," + trim(alpha));
        }

        @Override
        public void color3d(double red, double green, double blue) {
            calls.add("color3d:" + trim(red) + "," + trim(green) + "," + trim(blue));
        }

        @Override
        public void normal3f(float nx, float ny, float nz) {
            calls.add("normal3f:" + trim(nx) + "," + trim(ny) + "," + trim(nz));
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
            calls.add("blendFunc:" + src + "," + dst);
        }

        @Override
        public void bindTexture(int texture) {
            calls.add("bindTexture:" + texture);
        }

        private static String trim(float value) {
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }

        private static String trim(double value) {
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }
    }

    private static ByteBuffer floats(float... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * Float.BYTES).order(ByteOrder.nativeOrder());
        for (float value : values) {
            buffer.putFloat(value);
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer ubytes(int... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length);
        for (int value : values) {
            buffer.put((byte) value);
        }
        buffer.flip();
        return buffer;
    }

    private static PointerSnapshotGroup groupOf(PointerSnapshot... snapshots) {
        PointerSnapshotGroup group = new PointerSnapshotGroup();
        for (PointerSnapshot snapshot : snapshots) {
            group.add(snapshot);
        }
        return group;
    }

    @Test
    void drawArraysDecodesVertexTexCoordColor() {
        // 游戏 SpriteBatch/粒子的实际格式：顶点 2F + 纹理坐标 2F + 颜色 4UB（2 个顶点）
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 2, GL11.GL_FLOAT, 0,
                        floats(0.0f, 0.0f, 1.0f, 2.0f)),
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.TEX_COORD, 2, GL11.GL_FLOAT, 0,
                        floats(0.25f, 0.5f, 0.75f, 1.0f)),
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.COLOR, 4, GL11.GL_UNSIGNED_BYTE, 0,
                        ubytes(255, 0, 0, 255, 10, 20, 30, 40)));
        assertTrue(DrawCommandImmediateDecoder.canDecode(group), "全部 buffer 形式离散快照应可解码");

        RecordingSink sink = new RecordingSink();
        DrawCommandImmediateDecoder.replayDrawArrays(group, GL11.GL_QUADS, 0, 2, sink);

        assertEquals(List.of(
                "begin:7",
                "texCoord2f:0.25,0.5", "color4ub:255,0,0,255", "vertex2f:0,0",
                "texCoord2f:0.75,1", "color4ub:10,20,30,40", "vertex2f:1,2",
                "end"
        ), sink.calls);
    }

    @Test
    void drawArraysFirstOffsetSkipsLeadingVertices() {
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 2, GL11.GL_FLOAT, 0,
                        floats(0.0f, 0.0f, 1.0f, 1.0f, 2.0f, 2.0f)));

        RecordingSink sink = new RecordingSink();
        DrawCommandImmediateDecoder.replayDrawArrays(group, GL11.GL_QUADS, 1, 2, sink);

        assertEquals(List.of("begin:7", "vertex2f:1,1", "vertex2f:2,2", "end"), sink.calls);
    }

    @Test
    void explicitStrideIsHonored() {
        // stride=16：顶点交错布局（x,y,pad,pad 每 16 字节一个顶点）
        ByteBuffer interleaved = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        interleaved.putFloat(0.0f).putFloat(0.0f).putFloat(-1.0f).putFloat(-1.0f);
        interleaved.putFloat(1.0f).putFloat(1.0f).putFloat(-1.0f).putFloat(-1.0f);
        interleaved.putFloat(2.0f).putFloat(2.0f).putFloat(-1.0f).putFloat(-1.0f);
        interleaved.flip();
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 2, GL11.GL_FLOAT, 16,
                        interleaved));

        RecordingSink sink = new RecordingSink();
        DrawCommandImmediateDecoder.replayDrawArrays(group, GL11.GL_LINES, 0, 2, sink);

        assertEquals(List.of("begin:1", "vertex2f:0,0", "vertex2f:1,1", "end"), sink.calls);
    }

    @Test
    void drawElementsDecodesIndexedVertices() {
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 2, GL11.GL_FLOAT, 0,
                        floats(0.0f, 0.0f, 1.0f, 1.0f, 2.0f, 2.0f)));
        // 索引 [0, 2, 1]（short 视图）
        ByteBuffer indices = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder());
        indices.putShort((short) 0).putShort((short) 2).putShort((short) 1);
        indices.flip();

        RecordingSink sink = new RecordingSink();
        DrawCommandImmediateDecoder.replayDrawElements(group, GL11.GL_TRIANGLES, indices,
                DrawCommand.VIEW_SHORT, sink);

        assertEquals(List.of(
                "begin:4", "vertex2f:0,0", "vertex2f:2,2", "vertex2f:1,1", "end"
        ), sink.calls);
    }

    @Test
    void drawElementsByteViewReadsUnsigned() {
        // byte 视图：索引值以无符号读取——0x80 若按有符号字节读为 -128（越界崩溃），
        // 无符号读为 128 并命中第 128 个顶点
        float[] vertexData = new float[129 * 2];
        for (int i = 0; i < 129; i++) {
            vertexData[i * 2] = i;
            vertexData[i * 2 + 1] = i * 10.0f;
        }
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 2, GL11.GL_FLOAT, 0,
                        floats(vertexData)));
        ByteBuffer indices = ByteBuffer.allocateDirect(2);
        indices.put((byte) 0x80).put((byte) 1);
        indices.flip();

        RecordingSink sink = new RecordingSink();
        DrawCommandImmediateDecoder.replayDrawElements(group, GL11.GL_LINES, indices,
                DrawCommand.VIEW_BYTE, sink);

        assertEquals(List.of("begin:1", "vertex2f:128,1280", "vertex2f:1,10", "end"), sink.calls);
    }

    @Test
    void arrayElementDecodesSingleVertexWithoutBeginEnd() {
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 3, GL11.GL_FLOAT, 0,
                        floats(0.0f, 0.0f, 0.0f, 1.0f, 2.0f, 3.0f)));

        RecordingSink sink = new RecordingSink();
        DrawCommandImmediateDecoder.replayArrayElement(group, 1, sink);

        assertEquals(List.of("vertex3f:1,2,3"), sink.calls);
    }

    @Test
    void doublePrecisionVerticesDecode() {
        ByteBuffer vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        vertices.putDouble(0.5).putDouble(1.5).putDouble(2.5).putDouble(3.5);
        vertices.flip();
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 2, GL11.GL_DOUBLE, 0, vertices));

        RecordingSink sink = new RecordingSink();
        DrawCommandImmediateDecoder.replayDrawArrays(group, GL11.GL_POINTS, 0, 2, sink);

        assertEquals(List.of("begin:0", "vertex2d:0.5,1.5", "vertex2d:2.5,3.5", "end"), sink.calls);
    }

    @Test
    void vboOffsetSnapshotIsNotDecodable() {
        // VBO 偏移形式：数据在服务器端，display list 指针捕获语义正确，走常规路径
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.VERTEX, 2, GL11.GL_FLOAT, 0, 0L, 1),
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.TEX_COORD, 2, GL11.GL_FLOAT, 0, 8L, 1));
        assertFalse(DrawCommandImmediateDecoder.canDecode(group), "含 VBO 偏移快照的组不可解码");
    }

    @Test
    void interleavedSnapshotIsNotDecodable() {
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.INTERLEAVED, GL11.GL_T2F_V3F, 0, 0,
                        floats(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)));
        assertFalse(DrawCommandImmediateDecoder.canDecode(group), "INTERLEAVED 组不可解码");
    }

    @Test
    void emptyGroupIsNotDecodable() {
        assertFalse(DrawCommandImmediateDecoder.canDecode(new PointerSnapshotGroup()));
    }

    @Test
    void unsupportedVertexComponentCountFailsLoudly() {
        // 4 分量顶点超出 VertexSink 方法集：显式拒绝而非静默错绘/指针捕获
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX, 4, GL11.GL_FLOAT, 0,
                        floats(0.0f, 0.0f, 0.0f, 1.0f)));
        assertThrows(IllegalStateException.class,
                () -> DrawCommandImmediateDecoder.replayDrawArrays(group, GL11.GL_QUADS, 0, 1,
                        new RecordingSink()));
    }

    @Test
    void missingVertexPointerFailsLoudly() {
        PointerSnapshotGroup group = groupOf(
                PointerSnapshot.ofBuffer(PointerSnapshot.Kind.TEX_COORD, 2, GL11.GL_FLOAT, 0,
                        floats(0.0f, 0.0f)));
        assertThrows(IllegalStateException.class,
                () -> DrawCommandImmediateDecoder.replayDrawArrays(group, GL11.GL_QUADS, 0, 1,
                        new RecordingSink()));
    }
}
