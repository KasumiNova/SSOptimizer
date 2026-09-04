package github.kasuminova.ssoptimizer.bridge.opengl;

import java.util.Arrays;

/**
 * immediate 顶点流的录制缓冲（glBegin/glEnd 与 glVertex/glTexCoord/glColor/
 * glNormal3f 族），每个生产者线程持有一份（见 {@link BridgeSupport#vertexStream()}）。
 * <p>
 * 动机：固定管线逐顶点调用是录制侧最高频路径（wall profile 显示 glVertex2f/
 * glTexCoord2f 占主线程录制耗时大头）——原先每次调用分配一条 lambda 命令入队，
 * 一帧数万命令对象既费 CPU 又制造 GC 压力。本类把同一线程相邻的 immediate
 * 调用编码进字节流（1 字节操作码 + 定长载荷，大端序），落帧点：任一非流式
 * 命令插入 / 阻塞通道 drain-first / 帧尾 swap 之前，以及 glEnd——glEnd 在
 * 延迟落帧模式（{@code ssoptimizer.render.deferredGlEnd}，默认开）下仅在
 * 容量阈值触线时落帧，否则多段 begin..end 累积在同一缓冲（见
 * {@link BridgeSupport#flushVertexStreamOnGlEnd(RecordingContext)}）——
 * 由 {@link BridgeSupport#flushVertexStream()} 移交进池化的
 * {@link VertexBatchCommand} 落帧——批次自身零分配（流的缓冲
 * 逐线程复用，命令对象与回放缓冲池化复用）。
 * <p>
 * 顺序语义：流段命令在帧命令列表中占据其录制位置，回放逐指令原样执行——
 * 即使批次被中间命令切开（如 glBegin 后插入其他命令），两段流与该命令的执行
 * 顺序仍与原调用序列完全一致（glBegin..glEnd 之间插入非顶点调用本身是非法
 * GL 序列，此设计保证语义不进一步劣化，见 glCallList-in-begin 这类合法怪
 * 序列也能按原序回放）。段外状态指令（enable/disable/blendFunc/bindTexture）
 * 与矩阵指令（pushMatrix/popMatrix/loadIdentity/translatef/rotatef/scalef/
 * matrixMode）同样编码进流、按流内位置回放，见各编码方法的 javadoc。
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
    /** 流内 glEnable(cap)：在 glBegin/glEnd 段外执行，回放时改变启用状态。 */
    private static final byte OP_ENABLE = 15;
    /** 流内 glDisable(cap)：段外执行。 */
    private static final byte OP_DISABLE = 16;
    /** 流内 glBlendFunc(src, dst)：段外执行。 */
    private static final byte OP_BLEND_FUNC = 17;
    /** 流内 glBindTexture(TEXTURE_2D, texture)：段间（end..begin 之间）执行。 */
    private static final byte OP_BIND_TEXTURE = 18;
    /**
     * 精灵四边形融合指令（载荷 12 个 float：x0..y3 + texX/texY/texW/texH）：
     * 等价 begin(QUADS)+4×(texCoord+vertex)+end，见 {@link VertexSink#spriteQuad}。
     */
    private static final byte OP_SPRITE_QUAD = 19;
    /** 流内 glPushMatrix()：段外执行，回放时改变真实 GL 矩阵栈（见类 javadoc 矩阵指令节）。 */
    private static final byte OP_PUSH_MATRIX = 20;
    /** 流内 glPopMatrix()：段外执行。 */
    private static final byte OP_POP_MATRIX = 21;
    /** 流内 glLoadIdentity()：段外执行。 */
    private static final byte OP_LOAD_IDENTITY = 22;
    /** 流内 glTranslatef(x, y, z)：段外执行。 */
    private static final byte OP_TRANSLATE_F = 23;
    /** 流内 glRotatef(angle, x, y, z)：段外执行。 */
    private static final byte OP_ROTATE_F = 24;
    /** 流内 glScalef(x, y, z)：段外执行。 */
    private static final byte OP_SCALE_F = 25;
    /** 流内 glMatrixMode(mode)：段外执行（切换当前矩阵栈）。 */
    private static final byte OP_MATRIX_MODE = 26;

    /** 初始容量：一批典型 immediate 四边形组（百余顶点）约数 KB。 */
    private static final int INITIAL_CAPACITY = 4096;
    /** 预热统计窗口（批次 = 每次 {@link #transferBuffer()} 移交的段）。 */
    static final int PREWARM_WINDOW = 64;

    private byte[] buffer = new byte[INITIAL_CAPACITY];
    private int pos;
    /** 未收口的 glBegin 段深度（跨落帧保留，见 {@link #hasOpenSegment()}）。 */
    private int beginDepth;
    /**
     * 当前未落帧内容中是否含状态类指令（OP_ENABLE/OP_DISABLE/OP_BLEND_FUNC/
     * OP_BIND_TEXTURE 与流内矩阵指令 OP_PUSH_MATRIX/OP_POP_MATRIX/
     * OP_LOAD_IDENTITY/OP_TRANSLATE_F/OP_ROTATE_F/OP_SCALE_F/OP_MATRIX_MODE——
     * 回放时改变真实 GL 状态）：glEnd 延迟落帧模式下顶点流
     * 挂起期间不推进帧 commitSeq，{@link BridgeSupport#enqueueState} 的相邻性
     * 判据需凭本标记识别「挂起流内含状态改动」，保守失效去重缓存（否则挂起的
     * 流内状态指令会被去重跳过架空，见 DeferredGlEndTest）。
     */
    private boolean pendingStateOps;
    /** 近期批次的编码字节数环形记录（A3 预热：借新缓冲容量取窗口峰值，稳态零扩容）。 */
    private final int[] recentBatchLengths = new int[PREWARM_WINDOW];
    private int recentIndex;
    /**
     * 近期批次峰值（窗口内最大已编码字节数）：{@link #transferBuffer()} 换新
     * 缓冲时作为借取容量需求——相对历史最大（单调不减，罕见大段永久撑大内存
     * 需求），窗口峰值在突发批次滑出窗口后回落，借取容量与实际批次量级保持
     * 同步，池内缓冲容量既不浪费也不触发编码端渐进扩容（v45c profile：
     * {@code Arrays.copyOf} 1,893 样本的消除目标）。
     */
    private int prewarmCapacity = INITIAL_CAPACITY;

    boolean isEmpty() {
        return pos == 0;
    }

    /**
     * 当前是否有未收口的 glBegin 段（跨落帧追踪，不随 {@link #transferBuffer()}
     * 复位）：落帧时刻若段仍开放，本批次与「段开放期间插入的其他命令」构成
     * 病态切割序列（如 glCallList 插入 begin..end 之间），回放必须走逐指令
     * immediate 兜底（{@link ImmediateVertexSink}），数组化无法保持
     * 「真实 GL 段开放 + current 值逐指令推进」语义。
     */
    boolean hasOpenSegment() {
        return beginDepth > 0;
    }

    /**
     * 当前未落帧内容中是否含状态类指令（流内 enable/disable/blendFunc/
     * bindTexture 与矩阵指令族）：{@link BridgeSupport#enqueueState} 在去重
     * 相邻性判定前据此保守失效——挂起流未推进 commitSeq，但其回放会改变真实
     * GL 状态。
     */
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

    /**
     * 流内 glEnable(cap)：在 glBegin/glEnd 段外执行（回放时改变启用状态）。
     * 由 sprite 渲染路径（{@link GL11#streamEnable(int)}）等把状态设置编码进
     * 顶点流，避免「每 sprite 一条非流式状态命令」打断流段合并（v49 profile：
     * 主线程 flushVertexStream 4,253 样本，Sprite.render 2,432 的最大调用方）。
     */
    void enable(int cap) {
        putOp(OP_ENABLE);
        putInt(cap);
        pendingStateOps = true;
    }

    /** 流内 glDisable(cap)：段外执行，语义同 {@link #enable(int)}。 */
    void disable(int cap) {
        putOp(OP_DISABLE);
        putInt(cap);
        pendingStateOps = true;
    }

    /** 流内 glBlendFunc(src, dst)：段外执行。 */
    void blendFunc(int src, int dst) {
        putOp(OP_BLEND_FUNC);
        putInt(src);
        putInt(dst);
        pendingStateOps = true;
    }

    /**
     * 流内 glBindTexture(TEXTURE_2D, texture)：段间（上一段 glEnd 之后、
     * 下一段 glBegin 之前）执行——glBindTexture 在 glBegin/glEnd 段内非法，
     * 编码保证落在段边界处；同纹理连续 sprite 的重复绑定在回放时幂等冗余，
     * 换纹理的绑定打断的是「流内位置」而非「流段」——连续 sprite 的多个
     * begin..end 段仍合并为一条流命令，段与段之间由本指令衔接。
     */
    void bindTexture(int texture) {
        putOp(OP_BIND_TEXTURE);
        putInt(texture);
        pendingStateOps = true;
    }

    /**
     * 流内 glPushMatrix()：段外执行（矩阵指令在 glBegin/glEnd 段内非法，
     * 与流内状态指令同约定）。矩阵命令族（GL11.glPushMatrix 等）由此编码进
     * 顶点流，不再每条产生一个非流式命令——push/pop/transform 是 deferred
     * glEnd 落地后剩下的唯一「每条即 flush」高频命令族（每帧 400-1500 条，
     * 每条触发落帧 + lambda 命令分配 + frameLock 进入）；流内编码后跟随流
     * 走到下一个落帧点，回放按流内位置逐指令执行（保序语义同 OP_ENABLE 族，
     * 开关 {@code ssoptimizer.render.streamMatrixOps}）。
     */
    void pushMatrix() {
        putOp(OP_PUSH_MATRIX);
        pendingStateOps = true;
    }

    /** 流内 glPopMatrix()：段外执行，语义同 {@link #pushMatrix()}。 */
    void popMatrix() {
        putOp(OP_POP_MATRIX);
        pendingStateOps = true;
    }

    /** 流内 glLoadIdentity()：段外执行，语义同 {@link #pushMatrix()}。 */
    void loadIdentity() {
        putOp(OP_LOAD_IDENTITY);
        pendingStateOps = true;
    }

    /** 流内 glTranslatef(x, y, z)：段外执行，语义同 {@link #pushMatrix()}。 */
    void translatef(float x, float y, float z) {
        putOp(OP_TRANSLATE_F);
        putFloat(x);
        putFloat(y);
        putFloat(z);
        pendingStateOps = true;
    }

    /** 流内 glRotatef(angle, x, y, z)：段外执行，语义同 {@link #pushMatrix()}。 */
    void rotatef(float angle, float x, float y, float z) {
        putOp(OP_ROTATE_F);
        putFloat(angle);
        putFloat(x);
        putFloat(y);
        putFloat(z);
        pendingStateOps = true;
    }

    /** 流内 glScalef(x, y, z)：段外执行，语义同 {@link #pushMatrix()}。 */
    void scalef(float x, float y, float z) {
        putOp(OP_SCALE_F);
        putFloat(x);
        putFloat(y);
        putFloat(z);
        pendingStateOps = true;
    }

    /** 流内 glMatrixMode(mode)：段外执行（切换当前矩阵栈），语义同 {@link #pushMatrix()}。 */
    void matrixMode(int mode) {
        putOp(OP_MATRIX_MODE);
        putInt(mode);
        pendingStateOps = true;
    }

    /**
     * 精灵四边形融合指令（{@link VertexSink#spriteQuad} 的编码侧）：
     * sprite 渲染路径把 begin..end 整组调用压成一条流指令（1 字节操作码 +
     * 48 字节载荷），替代 13 次流调用约 78 字节编码（主线程每 sprite 的
     * 编码成本大头）。
     */
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

    /**
     * @return 当前已编码的字节数
     */
    int length() {
        return pos;
    }

    /** 测试用：当前预热容量（近期批次峰值，借缓冲与扩容的容量基准）。 */
    int prewarmCapacity() {
        return prewarmCapacity;
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

    /**
     * 移交当前缓冲的所有权给调用方（顶点批次落帧），本流换新缓冲继续编码。
     * 移交零拷贝——录制热路径上原先逐批次 {@link System#arraycopy} 拷贝进
     * 批次命令的耗时由此消除（v36 profile：{@code fillFrom} 2,303 样本）。
     * 移交出的缓冲由渲染线程执行完批次命令后经
     * {@link VertexStreamBufferPool} 归还，容量跨帧保留；新缓冲按近期批次
     * 峰值（预热）从池借取，稳态命中即不触发扩容（v45c profile：
     * {@code Arrays.copyOf} 1,893 样本的主要来源）。
     *
     * @return 已编码内容所在的缓冲（调用方接管所有权）
     */
    byte[] transferBuffer() {
        byte[] out = buffer;
        recordBatchLength(pos);
        buffer = BridgeSupport.acquireVertexStreamBuffer(prewarmCapacity);
        pos = 0;
        pendingStateOps = false;
        return out;
    }

    /** 记录本批次编码字节数并维护预热容量（窗口峰值；峰值被滑出窗口时重算）。 */
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

    /** 清空已编码内容（容量保留，同量级批次不再触发扩容）。 */
    void reset() {
        pos = 0;
        pendingStateOps = false;
    }

    /**
     * 解码字节流并对 {@code sink} 逐条回放（含 {@link VertexSink#startReplay()}/
     * {@link VertexSink#finishReplay()} 回调）；操作码未知说明流已损坏，直接抛异常。
     */
    static void replay(byte[] data, int length, VertexSink sink) {
        sink.startReplay();
        replayBody(data, length, sink);
        sink.finishReplay();
    }

    /**
     * {@link #replay} 的裸解码形式（不含回放开始/结束回调）：跨批次串合并执行
     * （{@link VertexBatchCommand#executeMerged}）时，串首/串尾回调由命令侧
     * 按串边界管理，串内每条批次只做裸解码。
     */
    static void replayBody(byte[] data, int length, VertexSink sink) {
        // 游标直读（大端，与 put 族编码约定一致）：每批次一次 ByteBuffer.wrap 的
        // HeapByteBuffer 分配与逐图元 getXxx 分派开销在渲染线程回放热路径上
        // 占比可观（cpu4 profile：wrap/<init> 865 样本 + HeapByteBuffer.get 族
        // 合计约占渲染线程 3.5%），此处消除。
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

    /** 大端解码（与 {@link #putInt} 编码序一致）。 */
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
