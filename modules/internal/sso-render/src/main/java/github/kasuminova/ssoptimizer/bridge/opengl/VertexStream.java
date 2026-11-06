package github.kasuminova.ssoptimizer.bridge.opengl;

import org.jctools.queues.SpscArrayQueue;

/** 每个生产者线程独占的 immediate 字节流；状态与矩阵指令也按录制位置编码。 */
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
    private static final byte OP_ENABLE = 15;
    private static final byte OP_DISABLE = 16;
    private static final byte OP_BLEND_FUNC = 17;
    private static final byte OP_BIND_TEXTURE = 18;
    private static final byte OP_SPRITE_QUAD = 19;
    private static final byte OP_PUSH_MATRIX = 20;
    private static final byte OP_POP_MATRIX = 21;
    private static final byte OP_LOAD_IDENTITY = 22;
    private static final byte OP_TRANSLATE_F = 23;
    private static final byte OP_ROTATE_F = 24;
    private static final byte OP_SCALE_F = 25;
    private static final byte OP_MATRIX_MODE = 26;

    private static final int INITIAL_CAPACITY = 4096;
    static final int PREWARM_WINDOW = 64;

    private byte[] buffer;
    private int pos;
    private int beginDepth;
    /** 挂起流含状态指令时必须使相邻状态去重失效。 */
    private boolean pendingStateOps;
    private final int[] recentBatchLengths = new int[PREWARM_WINDOW];
    private int recentIndex;
    private int prewarmCapacity = INITIAL_CAPACITY;
    /** 渲染线程单生产者、当前线程单消费者。 */
    private final SpscArrayQueue<byte[]> returnInbox;

    VertexStream() {
        this(null);
    }

    VertexStream(final SpscArrayQueue<byte[]> returnInbox) {
        this.returnInbox = returnInbox;
        // 初始缓冲也必须经池借取，保证归还与借出计数配对。
        this.buffer = BridgeSupport.acquireVertexStreamBuffer(INITIAL_CAPACITY, returnInbox);
    }

    boolean isEmpty() {
        return pos == 0;
    }

    /** 开放段跨落帧时必须逐指令回放，不能使用顶点数组。 */
    boolean hasOpenSegment() {
        return beginDepth > 0;
    }

    boolean hasPendingStateOps() {
        return pendingStateOps;
    }

    void begin(int mode) {
        putOp(OP_BEGIN);
        putInt(mode);
        beginDepth++;
    }

    void end() {
        putOp(OP_END);
        beginDepth--;
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

    void enable(int cap) {
        putOp(OP_ENABLE);
        putInt(cap);
        pendingStateOps = true;
    }

    void disable(int cap) {
        putOp(OP_DISABLE);
        putInt(cap);
        pendingStateOps = true;
    }

    void blendFunc(int src, int dst) {
        putOp(OP_BLEND_FUNC);
        putInt(src);
        putInt(dst);
        pendingStateOps = true;
    }

    void bindTexture(int texture) {
        putOp(OP_BIND_TEXTURE);
        putInt(texture);
        pendingStateOps = true;
    }

    void pushMatrix() {
        putOp(OP_PUSH_MATRIX);
        pendingStateOps = true;
    }

    void popMatrix() {
        putOp(OP_POP_MATRIX);
        pendingStateOps = true;
    }

    void loadIdentity() {
        putOp(OP_LOAD_IDENTITY);
        pendingStateOps = true;
    }

    void translatef(float x, float y, float z) {
        putOp(OP_TRANSLATE_F);
        putFloat(x);
        putFloat(y);
        putFloat(z);
        pendingStateOps = true;
    }

    void rotatef(float angle, float x, float y, float z) {
        putOp(OP_ROTATE_F);
        putFloat(angle);
        putFloat(x);
        putFloat(y);
        putFloat(z);
        pendingStateOps = true;
    }

    void scalef(float x, float y, float z) {
        putOp(OP_SCALE_F);
        putFloat(x);
        putFloat(y);
        putFloat(z);
        pendingStateOps = true;
    }

    void matrixMode(int mode) {
        putOp(OP_MATRIX_MODE);
        putInt(mode);
        pendingStateOps = true;
    }

    void spriteQuad(
            float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3,
            float texX, float texY, float texWidth, float texHeight) {
        putOp(OP_SPRITE_QUAD);
        putFloat(x0);
        putFloat(y0);
        putFloat(x1);
        putFloat(y1);
        putFloat(x2);
        putFloat(y2);
        putFloat(x3);
        putFloat(y3);
        putFloat(texX);
        putFloat(texY);
        putFloat(texWidth);
        putFloat(texHeight);
    }

    int length() {
        return pos;
    }

    int prewarmCapacity() {
        return prewarmCapacity;
    }

    /** dst 长度不得小于 length()。 */
    void copyTo(byte[] dst) {
        System.arraycopy(buffer, 0, dst, 0, pos);
    }

    /** 移交当前缓冲所有权，调用方负责最终归还池。 */
    byte[] transferBuffer() {
        byte[] out = buffer;
        recordBatchLength(pos);
        buffer = BridgeSupport.acquireVertexStreamBuffer(prewarmCapacity, returnInbox);
        pos = 0;
        pendingStateOps = false;
        return out;
    }

    private void recordBatchLength(int length) {
        int evicted = recentBatchLengths[recentIndex];
        recentBatchLengths[recentIndex] = length;
        recentIndex = (recentIndex + 1) % PREWARM_WINDOW;
        if (length >= prewarmCapacity) {
            prewarmCapacity = length;
        } else if (evicted == prewarmCapacity) {
            prewarmCapacity = INITIAL_CAPACITY;
            for (int windowLength : recentBatchLengths) {
                if (windowLength > prewarmCapacity) {
                    prewarmCapacity = windowLength;
                }
            }
        }
    }

    void reset() {
        pos = 0;
        pendingStateOps = false;
    }

    static void replay(byte[] data, int length, VertexSink sink) {
        sink.startReplay();
        replayBody(data, length, sink);
        sink.finishReplay();
    }

    static void replayBody(byte[] data, int length, VertexSink sink) {
        int p = 0;
        while (p < length) {
            switch (data[p++]) {
                case OP_BEGIN -> {
                    sink.begin(readInt(data, p));
                    p += 4;
                }
                case OP_END -> sink.end();
                case OP_VERTEX2F -> {
                    sink.vertex2f(readFloat(data, p), readFloat(data, p + 4));
                    p += 8;
                }
                case OP_VERTEX3F -> {
                    sink.vertex3f(readFloat(data, p), readFloat(data, p + 4), readFloat(data, p + 8));
                    p += 12;
                }
                case OP_VERTEX2D -> {
                    sink.vertex2d(readDouble(data, p), readDouble(data, p + 8));
                    p += 16;
                }
                case OP_VERTEX3D -> {
                    sink.vertex3d(readDouble(data, p), readDouble(data, p + 8), readDouble(data, p + 16));
                    p += 24;
                }
                case OP_TEXCOORD2F -> {
                    sink.texCoord2f(readFloat(data, p), readFloat(data, p + 4));
                    p += 8;
                }
                case OP_TEXCOORD2D -> {
                    sink.texCoord2d(readDouble(data, p), readDouble(data, p + 8));
                    p += 16;
                }
                case OP_COLOR4UB -> {
                    sink.color4ub(data[p], data[p + 1], data[p + 2], data[p + 3]);
                    p += 4;
                }
                case OP_COLOR3UB -> {
                    sink.color3ub(data[p], data[p + 1], data[p + 2]);
                    p += 3;
                }
                case OP_COLOR3F -> {
                    sink.color3f(readFloat(data, p), readFloat(data, p + 4), readFloat(data, p + 8));
                    p += 12;
                }
                case OP_COLOR4F -> {
                    sink.color4f(readFloat(data, p), readFloat(data, p + 4),
                            readFloat(data, p + 8), readFloat(data, p + 12));
                    p += 16;
                }
                case OP_COLOR3D -> {
                    sink.color3d(readDouble(data, p), readDouble(data, p + 8), readDouble(data, p + 16));
                    p += 24;
                }
                case OP_NORMAL3F -> {
                    sink.normal3f(readFloat(data, p), readFloat(data, p + 4), readFloat(data, p + 8));
                    p += 12;
                }
                case OP_ENABLE -> {
                    sink.enable(readInt(data, p));
                    p += 4;
                }
                case OP_DISABLE -> {
                    sink.disable(readInt(data, p));
                    p += 4;
                }
                case OP_BLEND_FUNC -> {
                    sink.blendFunc(readInt(data, p), readInt(data, p + 4));
                    p += 8;
                }
                case OP_BIND_TEXTURE -> {
                    sink.bindTexture(readInt(data, p));
                    p += 4;
                }
                case OP_SPRITE_QUAD -> {
                    sink.spriteQuad(
                            readFloat(data, p), readFloat(data, p + 4),
                            readFloat(data, p + 8), readFloat(data, p + 12),
                            readFloat(data, p + 16), readFloat(data, p + 20),
                            readFloat(data, p + 24), readFloat(data, p + 28),
                            readFloat(data, p + 32), readFloat(data, p + 36),
                            readFloat(data, p + 40), readFloat(data, p + 44));
                    p += 48;
                }
                case OP_PUSH_MATRIX -> sink.pushMatrix();
                case OP_POP_MATRIX -> sink.popMatrix();
                case OP_LOAD_IDENTITY -> sink.loadIdentity();
                case OP_TRANSLATE_F -> {
                    sink.translatef(readFloat(data, p), readFloat(data, p + 4), readFloat(data, p + 8));
                    p += 12;
                }
                case OP_ROTATE_F -> {
                    sink.rotatef(readFloat(data, p), readFloat(data, p + 4),
                            readFloat(data, p + 8), readFloat(data, p + 12));
                    p += 16;
                }
                case OP_SCALE_F -> {
                    sink.scalef(readFloat(data, p), readFloat(data, p + 4), readFloat(data, p + 8));
                    p += 12;
                }
                case OP_MATRIX_MODE -> {
                    sink.matrixMode(readInt(data, p));
                    p += 4;
                }
                default -> throw new IllegalStateException("[SSOptimizer] 顶点流损坏：未知操作码");
            }
        }
    }

    private static int readInt(final byte[] data, final int p) {
        return (data[p] << 24) | ((data[p + 1] & 0xFF) << 16)
                | ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
    }

    private static float readFloat(final byte[] data, final int p) {
        return Float.intBitsToFloat(readInt(data, p));
    }

    private static long readLong(final byte[] data, final int p) {
        return ((long) readInt(data, p) << 32) | (readInt(data, p + 4) & 0xFFFFFFFFL);
    }

    private static double readDouble(final byte[] data, final int p) {
        return Double.longBitsToDouble(readLong(data, p));
    }

    private void ensure(int additional) {
        if (pos + additional > buffer.length) {
            // 扩容必须经全局池借还，保证旧缓冲与新缓冲的借出计数配对。
            final int needed = Math.max(buffer.length * 2, pos + additional);
            final byte[] grown = BridgeSupport.acquireVertexStreamBuffer(needed, null);
            System.arraycopy(buffer, 0, grown, 0, pos);
            BridgeSupport.releaseVertexStreamBuffer(buffer, null);
            buffer = grown;
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
