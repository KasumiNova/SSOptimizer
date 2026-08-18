package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * immediate 顶点流的录制缓冲（glBegin/glEnd 与 glVertex/glTexCoord/glColor/
 * glNormal3f 族），每个生产者线程持有一份（见 {@link BridgeSupport#vertexStream()}）。
 * <p>
 * 动机：固定管线逐顶点调用是录制侧最高频路径（wall profile 显示 glVertex2f/
 * glTexCoord2f 占主线程录制耗时大头）——原先每次调用分配一条 lambda 命令入队，
 * 一帧数万命令对象既费 CPU 又制造 GC 压力。本类把同一线程相邻的 immediate
 * 调用编码进字节流（1 字节操作码 + 定长载荷，大端序），只在「非流式命令插入 /
 * glEnd / 阻塞通道 drain-first」时由 {@link BridgeSupport#flushVertexStream()}
 * 拷贝进池化的 {@link VertexBatchCommand} 落帧——批次自身零分配（流的缓冲
 * 逐线程复用，命令对象与回放缓冲池化复用）。
 * <p>
 * 顺序语义：流段命令在帧命令列表中占据其录制位置，回放逐指令原样执行——
 * 即使批次被中间命令切开（如 glBegin 后插入其他命令），两段流与该命令的执行
 * 顺序仍与原调用序列完全一致（glBegin..glEnd 之间插入非顶点调用本身是非法
 * GL 序列，此设计保证语义不进一步劣化，见 glCallList-in-begin 这类合法怪
 * 序列也能按原序回放）。
 * <p>
 * 本类非线程安全，线程隔离由 ThreadLocal 保证。
 */
final class VertexStream {
    private static final byte OP_BEGIN = 1;
    private static final byte OP_END = 2;
    private static final byte OP_VERTEX2F = 3;
    private static final byte OP_VERTEX3F = 4;
    private static final byte OP_VERTEX2D = 5;
    private static final byte OP_VERTEX3D = 6;
    private static final byte OP_TEXCOORD2F = 7;
    private static final byte OP_TEXCOORD2D = 8;
    private static final byte OP_COLOR4UB = 9;
    private static final byte OP_COLOR3UB = 10;
    private static final byte OP_COLOR3F = 11;
    private static final byte OP_COLOR4F = 12;
    private static final byte OP_COLOR3D = 13;
    private static final byte OP_NORMAL3F = 14;

    /** 初始容量：一批典型 immediate 四边形组（百余顶点）约数 KB。 */
    private static final int INITIAL_CAPACITY = 4096;

    private byte[] buffer = new byte[INITIAL_CAPACITY];
    private int pos;

    boolean isEmpty() {
        return pos == 0;
    }

    void begin(int mode) {
        putOp(OP_BEGIN);
        putInt(mode);
    }

    void end() {
        putOp(OP_END);
    }

    void vertex2f(float x, float y) {
        putOp(OP_VERTEX2F);
        putFloat(x);
        putFloat(y);
    }

    void vertex3f(float x, float y, float z) {
        putOp(OP_VERTEX3F);
        putFloat(x);
        putFloat(y);
        putFloat(z);
    }

    void vertex2d(double x, double y) {
        putOp(OP_VERTEX2D);
        putDouble(x);
        putDouble(y);
    }

    void vertex3d(double x, double y, double z) {
        putOp(OP_VERTEX3D);
        putDouble(x);
        putDouble(y);
        putDouble(z);
    }

    void texCoord2f(float s, float t) {
        putOp(OP_TEXCOORD2F);
        putFloat(s);
        putFloat(t);
    }

    void texCoord2d(double s, double t) {
        putOp(OP_TEXCOORD2D);
        putDouble(s);
        putDouble(t);
    }

    void color4ub(byte red, byte green, byte blue, byte alpha) {
        putOp(OP_COLOR4UB);
        putByte(red);
        putByte(green);
        putByte(blue);
        putByte(alpha);
    }

    void color3ub(byte red, byte green, byte blue) {
        putOp(OP_COLOR3UB);
        putByte(red);
        putByte(green);
        putByte(blue);
    }

    void color3f(float red, float green, float blue) {
        putOp(OP_COLOR3F);
        putFloat(red);
        putFloat(green);
        putFloat(blue);
    }

    void color4f(float red, float green, float blue, float alpha) {
        putOp(OP_COLOR4F);
        putFloat(red);
        putFloat(green);
        putFloat(blue);
        putFloat(alpha);
    }

    void color3d(double red, double green, double blue) {
        putOp(OP_COLOR3D);
        putDouble(red);
        putDouble(green);
        putDouble(blue);
    }

    void normal3f(float nx, float ny, float nz) {
        putOp(OP_NORMAL3F);
        putFloat(nx);
        putFloat(ny);
        putFloat(nz);
    }

    /**
     * @return 当前已编码的字节数
     */
    int length() {
        return pos;
    }

    /**
     * 把已编码内容拷贝到 {@code dst}（调用方保证容量足够）。内容拷贝后缓冲区
     * 所有权不移交——本流继续持有并复用自己的缓冲，避免逐批次分配（渲染线程
     * 侧的回放命令缓冲由 {@link VertexBatchCommand} 池化复用）。
     *
     * @param dst 目标缓冲，长度不得小于 {@link #length()}
     */
    void copyTo(byte[] dst) {
        System.arraycopy(buffer, 0, dst, 0, pos);
    }

    /** 清空已编码内容（容量保留，同量级批次不再触发扩容）。 */
    void reset() {
        pos = 0;
    }

    /** 解码字节流并对 {@code sink} 逐条回放；操作码未知说明流已损坏，直接抛异常。 */
    static void replay(byte[] data, int length, VertexSink sink) {
        ByteBuffer in = ByteBuffer.wrap(data, 0, length);
        while (in.hasRemaining()) {
            switch (in.get()) {
                case OP_BEGIN -> sink.begin(in.getInt());
                case OP_END -> sink.end();
                case OP_VERTEX2F -> sink.vertex2f(in.getFloat(), in.getFloat());
                case OP_VERTEX3F -> sink.vertex3f(in.getFloat(), in.getFloat(), in.getFloat());
                case OP_VERTEX2D -> sink.vertex2d(in.getDouble(), in.getDouble());
                case OP_VERTEX3D -> sink.vertex3d(in.getDouble(), in.getDouble(), in.getDouble());
                case OP_TEXCOORD2F -> sink.texCoord2f(in.getFloat(), in.getFloat());
                case OP_TEXCOORD2D -> sink.texCoord2d(in.getDouble(), in.getDouble());
                case OP_COLOR4UB -> sink.color4ub(in.get(), in.get(), in.get(), in.get());
                case OP_COLOR3UB -> sink.color3ub(in.get(), in.get(), in.get());
                case OP_COLOR3F -> sink.color3f(in.getFloat(), in.getFloat(), in.getFloat());
                case OP_COLOR4F -> sink.color4f(in.getFloat(), in.getFloat(), in.getFloat(), in.getFloat());
                case OP_COLOR3D -> sink.color3d(in.getDouble(), in.getDouble(), in.getDouble());
                case OP_NORMAL3F -> sink.normal3f(in.getFloat(), in.getFloat(), in.getFloat());
                default -> throw new IllegalStateException("[SSOptimizer] 顶点流损坏：未知操作码");
            }
        }
    }

    private void ensure(int additional) {
        if (pos + additional > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, pos + additional));
        }
    }

    private void putOp(byte op) {
        ensure(1);
        buffer[pos++] = op;
    }

    private void putByte(byte value) {
        ensure(1);
        buffer[pos++] = value;
    }

    private void putInt(int value) {
        ensure(4);
        buffer[pos++] = (byte) (value >>> 24);
        buffer[pos++] = (byte) (value >>> 16);
        buffer[pos++] = (byte) (value >>> 8);
        buffer[pos++] = (byte) value;
    }

    private void putLong(long value) {
        ensure(8);
        buffer[pos++] = (byte) (value >>> 56);
        buffer[pos++] = (byte) (value >>> 48);
        buffer[pos++] = (byte) (value >>> 40);
        buffer[pos++] = (byte) (value >>> 32);
        buffer[pos++] = (byte) (value >>> 24);
        buffer[pos++] = (byte) (value >>> 16);
        buffer[pos++] = (byte) (value >>> 8);
        buffer[pos++] = (byte) value;
    }

    private void putFloat(float value) {
        putInt(Float.floatToRawIntBits(value));
    }

    private void putDouble(double value) {
        putLong(Double.doubleToRawLongBits(value));
    }
}
