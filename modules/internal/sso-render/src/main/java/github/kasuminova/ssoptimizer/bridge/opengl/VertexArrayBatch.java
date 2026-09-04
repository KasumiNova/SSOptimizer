package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * immediate 顶点流的顶点数组合并回放器（{@link VertexSink} 的生产实现）：
 * 把字节流解码进渲染线程共享的直接缓冲（位置 3f / UV 2f / 颜色 4ub / 法线 3f），
 * 按 begin/end 图元段合并为 {@code glDrawArrays} 提交，替代逐顶点 JNI 调用
 * （渲染线程 profile：{@code VertexStream.replay} 占 31.6%，其中逐顶点
 * glVertex2f 等 immediate JNI 为最大头；合并后每图元段一次 draw call）。
 * <p>
 * 顶点快照语义与 immediate 逐指令回放等价：每个顶点落入数组时快照当前
 * texcoord/color/normal 状态；流内状态指令（enable/disable/blendFunc/bindTexture）
 * 与流内矩阵指令（pushMatrix/popMatrix/loadIdentity/translatef/rotatef/scalef/
 * matrixMode）打断挂起图元段——先排出全部待绘段（此刻的 GL 状态正是这些段
 * 被录制时看到的状态），再执行真实状态/矩阵调用，相对顺序与原调用序列逐指令
 * 一致；矩阵指令同时打断相邻 DRAW 合并（矩阵改变顶点变换，跨其合并不等价）。
 * <p>
 * 属性激活规则：某属性（texcoord/color/normal）在本流内首次出现前，顶点必须
 * 使用回放现场的外部当前值——对应图元段不启用该属性数组；首次出现后按段切换
 * 启用（段内首次激活时切分段，保证同一段内属性数组语义一致）。
 * <p>
 * 精度说明：double 载荷（vertex2d/3d、texCoord2d、color3d）收窄为 float——
 * 屏幕/世界坐标在 float 24 位尾数下的误差远低于亚像素，视觉无损；颜色 float
 * 载荷按 GL 归一化语义转 4ub（clamp [0,1] 后 *255 四舍五入）。
 * <p>
 * 线程模型：仅渲染线程使用（{@link VertexBatchCommand#execute()} 的共享实例），
 * 单线程无需同步。缓冲容量跨批次保留；超过 {@link #MAX_RETAINED_VERTICES}
 * 的罕见巨型批次在下一批次回放开始时回落到初始容量，避免偶发大段永久撑大
 * 直接内存。
 * <p>
 * 批次内状态去重与 DRAW 合并：draw 不会改变 cap 开关 / blendFunc / 纹理绑定的
 * 值，因此批次内（= 帧命令列表中一段连续区间，外部命令必然已切开流）可安全
 * 做三件等价变换——
 * <ul>
 *   <li>冗余状态指令剔除：同值的重复 enable/disable/blendFunc/bindTexture 跳过；
 *   未被任何 draw 观测的 blendFunc/bindTexture 直接改写参数；
 *   disable→enable（及反向）且无 draw 间隔的状态对净效果为零，成对抵消；</li>
 *   <li>相邻 DRAW 合并：同图元模式、同属性标志、顶点区间相邻且中间仅隔着已抵消
 *   状态对的 DRAW 段合并为一次 {@code glDrawArrays}（sprite 序列从「每 sprite
 *   一组状态调用 + 一次 draw」收敛为「一组状态调用 + 每纹理一次 draw」，
 *   渲染线程 profile：流内 enable/disable 是回放侧第二大头）。</li>
 * </ul>
 */
final class VertexArrayBatch implements VertexSink {
    /** 初始顶点容量（个）：覆盖典型 sprite 批次。 */
    private static final int INITIAL_VERTEX_CAPACITY = 1024;
    /** 保留容量上限（顶点个）：超过的批次在下一批次回放开始时回落初始容量。 */
    private static final int MAX_RETAINED_VERTICES = 1 << 20;

    /**
     * 逐操作 GL 错误探针（诊断设施）：与 RenderQueueImpl 的 command 级探针同开关，
     * 在 {@link #executeGl()} 的每个回放操作后取一次 glGetError，精确定位产生
     * 错误的操作种类与参数（用于「VertexBatchCommand 执行后滞留 0x501」类定位）。
     */
    private static final boolean OP_PROBE = "command".equals(System.getProperty(
            github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl.GL_ERROR_PROBE_PROPERTY, "off"));
    private static final org.apache.log4j.Logger OP_PROBE_LOG = org.apache.log4j.Logger.getLogger(VertexArrayBatch.class);

    // 绘制段属性标志（texcoord/color/normal 数组启用）
    static final int FLAG_TEX   = 1;
    static final int FLAG_COLOR = 2;
    static final int FLAG_NORM  = 4;

    // 操作种类
    static final int OP_DRAW         = 0; // a=mode, b=first, c=count, flags=段属性标志
    static final int OP_ENABLE       = 1; // a=cap
    static final int OP_DISABLE      = 2; // a=cap
    static final int OP_BLEND_FUNC   = 3; // a=src, b=dst
    static final int OP_BIND_TEXTURE = 4; // a=texture
    static final int OP_PUSH_MATRIX   = 5;  // 无参
    static final int OP_POP_MATRIX    = 6;  // 无参
    static final int OP_LOAD_IDENTITY = 7;  // 无参
    static final int OP_TRANSLATE_F   = 8;  // a/b/c = x/y/z 的 float 位模式
    static final int OP_ROTATE_F      = 9;  // a/b/c/d = angle/x/y/z 的 float 位模式
    static final int OP_SCALE_F       = 10; // a/b/c = x/y/z 的 float 位模式
    static final int OP_MATRIX_MODE   = 11; // a=mode
    /** 被批次内状态去重抵消的操作（{@link #executeGl()} 跳过）。 */
    static final int OP_NOOP         = -1;

    private static final int SEG_STRIDE = 4; // mode, first, count, flags
    private static final int OP_STRIDE  = 5; // kind, a, b, c, d（DRAW 的标志在 opFlags 平行数组）

    private ByteBuffer  posBytes;
    private ByteBuffer  texBytes;
    private ByteBuffer  colorBytes;
    private ByteBuffer  normBytes;
    private FloatBuffer posBuf;
    private FloatBuffer texBuf;
    private ByteBuffer  colorBuf;
    private FloatBuffer normBuf;

    /** 挂起图元段（mode, first, count, flags 四元组），状态指令插入时整批排出。 */
    private int[] segments = new int[SEG_STRIDE * 64];
    private int   segmentCount;
    /** 回放操作列表（kind, a, b, c 四元组）与 DRAW 段属性标志平行数组。 */
    private int[] ops = new int[OP_STRIDE * 128];
    private int[] opFlags = new int[128];
    private int   opCount;

    private int vertexCount;

    // ── 批次内状态去重（批次 = 帧命令列表中一段连续区间，外部命令必然已切开流，
    //    因此批次内只有本批的 DRAW 与状态指令，无旁路状态变更）──
    /** 已追加 DRAW 操作计数（合并也算）：「无 draw 间隔」抵消判定的时戳。 */
    private int drawStamp;
    /** 各 cap 最近一次 enable/disable 的记录（线性扫描，批次内 cap 种类极少）。 */
    private int[]     capIds       = new int[8];
    private int[]     capOpIndex   = new int[8];
    private int[]     capDrawStamp = new int[8];
    private boolean[] capIsEnable  = new boolean[8];
    private int       capEntryCount;
    /** 批次内最近一次 blendFunc（draw 不改变 blendFunc 值，同值重复恒冗余）。 */
    private boolean blendKnown;
    private int     lastBlendSrc;
    private int     lastBlendDst;
    private int     lastBlendOp;
    private int     lastBlendDrawStamp;
    /** 批次内最近一次纹理绑定（同上）。 */
    private boolean bindKnown;
    private int     lastBind;
    private int     lastBindOp;
    private int     lastBindDrawStamp;

    // 当前属性状态（顶点落入数组时快照）
    private float curS;
    private float curT;
    private byte  curR;
    private byte  curG;
    private byte  curB;
    private byte  curA;
    private float curNx;
    private float curNy;
    private float curNz = 1.0f;
    /** 本流内各属性是否已出现过（未出现的属性段不启用数组，顶点用外部当前值）。 */
    private int   definedFlags;

    // 打开中的图元段
    private int openMode = -1;
    private int openStart;
    private int openFlags;

    VertexArrayBatch() {
        allocateBuffers(INITIAL_VERTEX_CAPACITY);
    }

    private void allocateBuffers(final int vertices) {
        posBytes = ByteBuffer.allocateDirect(vertices * 12).order(ByteOrder.nativeOrder());
        texBytes = ByteBuffer.allocateDirect(vertices * 8).order(ByteOrder.nativeOrder());
        colorBytes = ByteBuffer.allocateDirect(vertices * 4).order(ByteOrder.nativeOrder());
        normBytes = ByteBuffer.allocateDirect(vertices * 12).order(ByteOrder.nativeOrder());
        posBuf = posBytes.asFloatBuffer();
        texBuf = texBytes.asFloatBuffer();
        colorBuf = colorBytes;
        normBuf = normBytes.asFloatBuffer();
    }

    /** 回放开始：清空上一批次的全部累积状态（由 {@link VertexStream#replay} 回调）。 */
    @Override
    public void startReplay() {
        if (vertexCapacity() > MAX_RETAINED_VERTICES) {
            allocateBuffers(INITIAL_VERTEX_CAPACITY);
        }
        // 上一批次 executeGl 曾把 limit 收窄到已用区间（LWJGL 指针调用按
        // remaining 取量）——绝对 put 按 limit 检查下标，跨批次必须复位为全容量。
        posBuf.clear();
        texBuf.clear();
        colorBuf.clear();
        normBuf.clear();
        segmentCount = 0;
        opCount = 0;
        vertexCount = 0;
        definedFlags = 0;
        curS = 0.0f;
        curT = 0.0f;
        curR = 0;
        curG = 0;
        curB = 0;
        curA = 0;
        curNx = 0.0f;
        curNy = 0.0f;
        curNz = 1.0f;
        openMode = -1;
        drawStamp = 0;
        capEntryCount = 0;
        blendKnown = false;
        bindKnown = false;
    }

    /** 回放结束：排出残余图元段（由 {@link VertexStream#replay} 回调）。 */
    @Override
    public void finishReplay() {
        flushSegmentsToOps();
    }

    @Override
    public void begin(final int mode) {
        closeOpenSegment();
        openMode = mode;
        openStart = vertexCount;
        openFlags = definedFlags;
    }

    @Override
    public void end() {
        closeOpenSegment();
    }

    @Override
    public void vertex2f(final float x, final float y) {
        appendVertex(x, y, 0.0f);
    }

    @Override
    public void vertex3f(final float x, final float y, final float z) {
        appendVertex(x, y, z);
    }

    @Override
    public void vertex2d(final double x, final double y) {
        appendVertex((float) x, (float) y, 0.0f);
    }

    @Override
    public void vertex3d(final double x, final double y, final double z) {
        appendVertex((float) x, (float) y, (float) z);
    }

    @Override
    public void texCoord2f(final float s, final float t) {
        activateAttribute(FLAG_TEX);
        curS = s;
        curT = t;
    }

    @Override
    public void texCoord2d(final double s, final double t) {
        activateAttribute(FLAG_TEX);
        curS = (float) s;
        curT = (float) t;
    }

    @Override
    public void color4ub(final byte red, final byte green, final byte blue, final byte alpha) {
        activateAttribute(FLAG_COLOR);
        curR = red;
        curG = green;
        curB = blue;
        curA = alpha;
    }

    @Override
    public void color3ub(final byte red, final byte green, final byte blue) {
        color4ub(red, green, blue, (byte) 255);
    }

    @Override
    public void color3f(final float red, final float green, final float blue) {
        color4ub(floatToUByte(red), floatToUByte(green), floatToUByte(blue), (byte) 255);
    }

    @Override
    public void color4f(final float red, final float green, final float blue, final float alpha) {
        color4ub(floatToUByte(red), floatToUByte(green), floatToUByte(blue), floatToUByte(alpha));
    }

    @Override
    public void color3d(final double red, final double green, final double blue) {
        color3f((float) red, (float) green, (float) blue);
    }

    @Override
    public void normal3f(final float nx, final float ny, final float nz) {
        activateAttribute(FLAG_NORM);
        curNx = nx;
        curNy = ny;
        curNz = nz;
    }

    /**
     * {@link VertexSink#spriteQuad} 的数组合并形态：tex 属性激活后 4 个顶点
     * 直写数组（每顶点按角点 tex 快照，颜色/法线沿用当前快照语义），
     * 段管理与逐指令展开等价（begin/end 对保证与相邻段的正常衔接与合并）。
     */
    @Override
    public void spriteQuad(
            final float x0, final float y0, final float x1, final float y1,
            final float x2, final float y2, final float x3, final float y3,
            final float texX, final float texY, final float texWidth, final float texHeight) {
        activateAttribute(FLAG_TEX);
        begin(GL11.GL_QUADS);
        curS = texX;
        curT = texY;
        appendVertex(x0, y0, 0.0f);
        curT = texY + texHeight;
        appendVertex(x1, y1, 0.0f);
        curS = texX + texWidth;
        appendVertex(x2, y2, 0.0f);
        curT = texY;
        appendVertex(x3, y3, 0.0f);
        end();
    }

    /**
     * 流内 glEnable（批次内去重）：cap 在本批次已启用时重复 enable 恒冗余
     * （draw 不改变 cap 状态）；若最近一条是同 cap 的 disable 且期间无 draw，
     * 两条净效果为零，成对抵消（disable 标 {@link #OP_NOOP}，enable 不落）。
     */
    @Override
    public void enable(final int cap) {
        flushSegmentsToOps();
        final int idx = findCap(cap);
        if (idx >= 0) {
            if (capIsEnable[idx]) {
                return;
            }
            if (capDrawStamp[idx] == drawStamp) {
                ops[capOpIndex[idx] * OP_STRIDE] = OP_NOOP;
                removeCapEntry(idx);
                return;
            }
        }
        addOp(OP_ENABLE, cap, 0, 0, 0, 0);
        putCapEntry(cap, opCount - 1, true);
    }

    /** 流内 glDisable：与 {@link #enable(int)} 镜像的去重/抵消。 */
    @Override
    public void disable(final int cap) {
        flushSegmentsToOps();
        final int idx = findCap(cap);
        if (idx >= 0) {
            if (!capIsEnable[idx]) {
                return;
            }
            if (capDrawStamp[idx] == drawStamp) {
                ops[capOpIndex[idx] * OP_STRIDE] = OP_NOOP;
                removeCapEntry(idx);
                return;
            }
        }
        addOp(OP_DISABLE, cap, 0, 0, 0, 0);
        putCapEntry(cap, opCount - 1, false);
    }

    /**
     * 流内 glBlendFunc（批次内去重）：与批次内当前值相同则跳过；上一条
     * blendFunc 未被任何 draw 观测时直接改写其参数（中间值无观察者）。
     */
    @Override
    public void blendFunc(final int src, final int dst) {
        flushSegmentsToOps();
        if (blendKnown) {
            if (lastBlendSrc == src && lastBlendDst == dst) {
                return;
            }
            if (lastBlendDrawStamp == drawStamp) {
                ops[lastBlendOp * OP_STRIDE + 1] = src;
                ops[lastBlendOp * OP_STRIDE + 2] = dst;
                lastBlendSrc = src;
                lastBlendDst = dst;
                return;
            }
        }
        addOp(OP_BLEND_FUNC, src, dst, 0, 0, 0);
        blendKnown = true;
        lastBlendSrc = src;
        lastBlendDst = dst;
        lastBlendOp = opCount - 1;
        lastBlendDrawStamp = drawStamp;
    }

    /** 流内 glBindTexture：去重规则同 {@link #blendFunc(int, int)}。 */
    @Override
    public void bindTexture(final int texture) {
        flushSegmentsToOps();
        if (bindKnown) {
            if (lastBind == texture) {
                return;
            }
            if (lastBindDrawStamp == drawStamp) {
                ops[lastBindOp * OP_STRIDE + 1] = texture;
                lastBind = texture;
                return;
            }
        }
        addOp(OP_BIND_TEXTURE, texture, 0, 0, 0, 0);
        bindKnown = true;
        lastBind = texture;
        lastBindOp = opCount - 1;
        lastBindDrawStamp = drawStamp;
    }

    /**
     * 流内 glPushMatrix（矩阵指令族统一形态）：先排出挂起图元段——此刻的矩阵
     * 状态正是这些段被录制时看到的状态，再追加矩阵操作，回放相对顺序与原调用
     * 序列逐指令一致。矩阵操作天然打断相邻 DRAW 合并（见 {@link #addOp}）；
     * 矩阵不改变 cap/blend/纹理绑定，批次内状态去重缓存不受其影响。
     */
    @Override
    public void pushMatrix() {
        flushSegmentsToOps();
        addOp(OP_PUSH_MATRIX, 0, 0, 0, 0, 0);
    }

    /** 流内 glPopMatrix：语义同 {@link #pushMatrix()}。 */
    @Override
    public void popMatrix() {
        flushSegmentsToOps();
        addOp(OP_POP_MATRIX, 0, 0, 0, 0, 0);
    }

    /** 流内 glLoadIdentity：语义同 {@link #pushMatrix()}。 */
    @Override
    public void loadIdentity() {
        flushSegmentsToOps();
        addOp(OP_LOAD_IDENTITY, 0, 0, 0, 0, 0);
    }

    /** 流内 glTranslatef（float 参数以位模式存入 int 参数槽）：语义同 {@link #pushMatrix()}。 */
    @Override
    public void translatef(final float x, final float y, final float z) {
        flushSegmentsToOps();
        addOp(OP_TRANSLATE_F, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y),
                Float.floatToRawIntBits(z), 0, 0);
    }

    /** 流内 glRotatef：语义同 {@link #translatef(float, float, float)}。 */
    @Override
    public void rotatef(final float angle, final float x, final float y, final float z) {
        flushSegmentsToOps();
        addOp(OP_ROTATE_F, Float.floatToRawIntBits(angle), Float.floatToRawIntBits(x),
                Float.floatToRawIntBits(y), Float.floatToRawIntBits(z), 0);
    }

    /** 流内 glScalef：语义同 {@link #translatef(float, float, float)}。 */
    @Override
    public void scalef(final float x, final float y, final float z) {
        flushSegmentsToOps();
        addOp(OP_SCALE_F, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y),
                Float.floatToRawIntBits(z), 0, 0);
    }

    /** 流内 glMatrixMode（切换当前矩阵栈）：语义同 {@link #pushMatrix()}。 */
    @Override
    public void matrixMode(final int mode) {
        flushSegmentsToOps();
        addOp(OP_MATRIX_MODE, mode, 0, 0, 0, 0);
    }

    private int findCap(final int cap) {
        for (int i = 0; i < capEntryCount; i++) {
            if (capIds[i] == cap) {
                return i;
            }
        }
        return -1;
    }

    private void putCapEntry(final int cap, final int opIndex, final boolean isEnable) {
        final int idx = findCap(cap);
        if (idx >= 0) {
            capOpIndex[idx] = opIndex;
            capDrawStamp[idx] = drawStamp;
            capIsEnable[idx] = isEnable;
            return;
        }
        if (capEntryCount == capIds.length) {
            final int grown = capIds.length * 2;
            capIds = Arrays.copyOf(capIds, grown);
            capOpIndex = Arrays.copyOf(capOpIndex, grown);
            capDrawStamp = Arrays.copyOf(capDrawStamp, grown);
            capIsEnable = Arrays.copyOf(capIsEnable, grown);
        }
        capIds[capEntryCount] = cap;
        capOpIndex[capEntryCount] = opIndex;
        capDrawStamp[capEntryCount] = drawStamp;
        capIsEnable[capEntryCount] = isEnable;
        capEntryCount++;
    }

    /** 抵消后移除 cap 记录（末位交换；条目内记录的是 ops 下标，与条目顺序无关）。 */
    private void removeCapEntry(final int idx) {
        final int last = capEntryCount - 1;
        capIds[idx] = capIds[last];
        capOpIndex[idx] = capOpIndex[last];
        capDrawStamp[idx] = capDrawStamp[last];
        capIsEnable[idx] = capIsEnable[last];
        capEntryCount = last;
    }

    /**
     * 属性首次激活：若当前段已累积顶点，按「激活前后属性数组语义不同」切分——
     * 旧段以无该属性的标志收口，新段从当前顶点起带该属性继续；若当前段尚无
     * 顶点，该属性对整段生效，直接更新段标志。
     */
    private void activateAttribute(final int flag) {
        if ((definedFlags & flag) != 0) {
            return;
        }
        definedFlags |= flag;
        if (openMode == -1) {
            return;
        }
        if (vertexCount > openStart) {
            final int mode = openMode;
            closeOpenSegment();
            openMode = mode;
            openStart = vertexCount;
        }
        openFlags = definedFlags;
    }

    private void appendVertex(final float x, final float y, final float z) {
        ensureVertexCapacity(vertexCount + 1);
        final int vi = vertexCount;
        posBuf.put(vi * 3, x);
        posBuf.put(vi * 3 + 1, y);
        posBuf.put(vi * 3 + 2, z);
        texBuf.put(vi * 2, curS);
        texBuf.put(vi * 2 + 1, curT);
        colorBuf.put(vi * 4, curR);
        colorBuf.put(vi * 4 + 1, curG);
        colorBuf.put(vi * 4 + 2, curB);
        colorBuf.put(vi * 4 + 3, curA);
        normBuf.put(vi * 3, curNx);
        normBuf.put(vi * 3 + 1, curNy);
        normBuf.put(vi * 3 + 2, curNz);
        vertexCount = vi + 1;
    }

    private void closeOpenSegment() {
        if (openMode == -1) {
            return;
        }
        final int count = vertexCount - openStart;
        if (count > 0) {
            if (segmentCount * SEG_STRIDE == segments.length) {
                final int[] grown = new int[segments.length * 2];
                System.arraycopy(segments, 0, grown, 0, segments.length);
                segments = grown;
            }
            final int base = segmentCount * SEG_STRIDE;
            segments[base] = openMode;
            segments[base + 1] = openStart;
            segments[base + 2] = count;
            segments[base + 3] = openFlags;
            segmentCount++;
        }
        openMode = -1;
    }

    /** 把挂起图元段排出为 DRAW 操作（状态指令插入前 / 回放结束的段落收口）。 */
    private void flushSegmentsToOps() {
        closeOpenSegment();
        for (int i = 0; i < segmentCount; i++) {
            final int base = i * SEG_STRIDE;
            addOp(OP_DRAW, segments[base], segments[base + 1], segments[base + 2], 0, segments[base + 3]);
        }
        segmentCount = 0;
    }

    /**
     * 相邻 DRAW 合并的图元边界资格判定。
     * <p>
     * 离散型图元（POINTS/LINES/TRIANGLES/QUADS）的每图元顶点数固定，合并只是
     * 把顶点序列接续——前一段顶点数必须是每图元顶点数的整数倍（前段末尾恰落在
     * 图元边界上），合并后各图元配对才与分开绘制一致。若前一段顶点数不对齐
     * （如 GL_LINES 奇数顶点），分开绘制时末尾未配对顶点被 GL 忽略，合并后该
     * 顶点会与后一段首顶点错误配对、后续图元整体偏移一位——实机「线段对角
     * 交叉」症状（战役地图刻度线交叉错乱、战斗目标框画成 X 形）的根因。
     * <p>
     * 连续型图元（LINE_STRIP/LINE_LOOP/TRIANGLE_STRIP/TRIANGLE_FAN/
     * QUAD_STRIP/POLYGON）的图元结构跨段延续，合并会在两段顶点之间引入额外的
     * 连接图元（如两个 LINE_STRIP 合并后中间多出一条连线），无论顶点数是否
     * 对齐都不安全，一律不合并；未知图元模式按不安全处理。
     *
     * @param mode      前一段的图元模式（与当前段相同，调用方已校验）
     * @param prevCount 前一段的顶点数
     */
    private static boolean canMergeDraw(final int mode, final int prevCount) {
        final int stride = switch (mode) {
            case GL11.GL_POINTS -> 1;
            case GL11.GL_LINES -> 2;
            case GL11.GL_TRIANGLES -> 3;
            case GL11.GL_QUADS -> 4;
            default -> 0;
        };
        return stride > 0 && prevCount % stride == 0;
    }

    private void addOp(final int kind, final int a, final int b, final int c, final int d, final int flags) {
        if (kind == OP_DRAW) {
            // 相邻 DRAW 合并：同图元模式/属性标志、顶点区间相邻，且中间仅隔着
            // 已抵消（OP_NOOP）的状态对——NOOP 对净状态为零，跨它合并等价；
            // 且前一段顶点数须与图元边界对齐（见 canMergeDraw）——否则合并后
            // 顶点配对整体偏移，产生跨段错误图元（实机「线段对角交叉」根因）。
            // 矩阵指令介于两 DRAW 之间时 prev 落在矩阵操作上，合并自然不成立
            // （矩阵改变顶点变换，跨其合并语义不等价）。
            int prev = opCount - 1;
            while (prev >= 0 && ops[prev * OP_STRIDE] == OP_NOOP) {
                prev--;
            }
            if (prev >= 0 && ops[prev * OP_STRIDE] == OP_DRAW
                    && ops[prev * OP_STRIDE + 1] == a
                    && opFlags[prev] == flags
                    && ops[prev * OP_STRIDE + 2] + ops[prev * OP_STRIDE + 3] == b
                    && canMergeDraw(a, ops[prev * OP_STRIDE + 3])) {
                ops[prev * OP_STRIDE + 3] += c;
                drawStamp++;
                return;
            }
            drawStamp++;
        }
        if (opCount * OP_STRIDE == ops.length) {
            final int[] grownOps = new int[ops.length * 2];
            System.arraycopy(ops, 0, grownOps, 0, ops.length);
            ops = grownOps;
            final int[] grownFlags = new int[opFlags.length * 2];
            System.arraycopy(opFlags, 0, grownFlags, 0, opFlags.length);
            opFlags = grownFlags;
        }
        final int base = opCount * OP_STRIDE;
        ops[base] = kind;
        ops[base + 1] = a;
        ops[base + 2] = b;
        ops[base + 3] = c;
        ops[base + 4] = d;
        opFlags[opCount] = flags;
        opCount++;
    }

    private void ensureVertexCapacity(final int required) {
        if (required <= vertexCapacity()) {
            return;
        }
        int newCapacity = vertexCapacity() * 2;
        while (newCapacity < required) {
            newCapacity *= 2;
        }
        final ByteBuffer newPos = ByteBuffer.allocateDirect(newCapacity * 12).order(ByteOrder.nativeOrder());
        final ByteBuffer newTex = ByteBuffer.allocateDirect(newCapacity * 8).order(ByteOrder.nativeOrder());
        final ByteBuffer newColor = ByteBuffer.allocateDirect(newCapacity * 4).order(ByteOrder.nativeOrder());
        final ByteBuffer newNorm = ByteBuffer.allocateDirect(newCapacity * 12).order(ByteOrder.nativeOrder());
        copyUsed(posBytes, vertexCount * 12, newPos);
        copyUsed(texBytes, vertexCount * 8, newTex);
        copyUsed(colorBytes, vertexCount * 4, newColor);
        copyUsed(normBytes, vertexCount * 12, newNorm);
        posBytes = newPos;
        texBytes = newTex;
        colorBytes = newColor;
        normBytes = newNorm;
        posBuf = newPos.asFloatBuffer();
        texBuf = newTex.asFloatBuffer();
        colorBuf = newColor;
        normBuf = newNorm.asFloatBuffer();
    }

    private static void copyUsed(final ByteBuffer from, final int usedBytes, final ByteBuffer to) {
        from.clear();
        from.limit(usedBytes);
        to.put(from);
        // put 后 position 停在已用末尾——asFloatBuffer() 视图按剩余区间取界，
        // 必须复位 position 才能让视图覆盖全容量（绝对 put 写入依赖完整界限）。
        to.clear();
    }

    private int vertexCapacity() {
        return posBytes.capacity() / 12;
    }

    /**
     * 在渲染线程执行本批次的全部回放操作（真实 GL 调用）。
     * 客户端数组指针在整个批次内稳定（缓冲不迁移），首个 DRAW 操作时设置一次；
     * 批次结束后客户端数组状态复原为全禁用——与 immediate 回放不持有客户端
     * 数组状态的语义一致，避免影响后续执行路径（DrawCommand 的指针快照应用、
     * 显示列表等）。
     * <p>
     * 执行完毕后排干操作/段/顶点计数（防止串内 immediate 兜底批次的排干调用
     * 重复执行上一串的已执行操作）；current 值快照（cur*）与 definedFlags、
     * 去重状态跨批次保留，由串首 {@link #startReplay()} 全量重置。
     * <p>
     * current 值回同步：段内 color/texCoord/normal 只烘焙进顶点数组，真实 GL
     * 的 current 值必须在本批结束时推进到流内最后设置的值——批外消费者
     * （glCallList 显示列表的调用时刻纹理调制、glGet 回读）依赖该语义
     * （v37~v43 数组化黑屏的根因之一，
     * 见 docs/design/render-logic-separation-feasibility.md）。
     */
    void executeGl() {
        if (opCount == 0) {
            // 纯属性流（无 draw 无状态指令）也要把 current 值落到真实 GL
            syncCurrentValues();
            return;
        }
        sealBuffers();

        boolean pointersSet = false;
        boolean vertexArrayOn = false;
        boolean texOn = false;
        boolean colorOn = false;
        boolean normOn = false;
        for (int i = 0; i < opCount; i++) {
            final int base = i * OP_STRIDE;
            switch (ops[base]) {
                case OP_DRAW -> {
                    if (!pointersSet) {
                        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                        probeOpError("enableClientState(VERTEX_ARRAY)");
                        vertexArrayOn = true;
                        GL11.glVertexPointer(3, 0, posBuf);
                        probeOpError("glVertexPointer(3,0,pos)");
                        GL11.glTexCoordPointer(2, 0, texBuf);
                        probeOpError("glTexCoordPointer(2,0,tex)");
                        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 0, colorBuf);
                        probeOpError("glColorPointer(4,UB,0,color)");
                        // LWJGL2 双参重载 glNormalPointer(int stride, FloatBuffer)：首参是 stride 而非 type（type 硬编码 GL_FLOAT）
                        GL11.glNormalPointer(0, normBuf);
                        probeOpError("glNormalPointer(0,norm)");
                        pointersSet = true;
                    }
                    final int flags = opFlags[i];
                    texOn = switchClientState(GL11.GL_TEXTURE_COORD_ARRAY, (flags & FLAG_TEX) != 0, texOn);
                    colorOn = switchClientState(GL11.GL_COLOR_ARRAY, (flags & FLAG_COLOR) != 0, colorOn);
                    normOn = switchClientState(GL11.GL_NORMAL_ARRAY, (flags & FLAG_NORM) != 0, normOn);
                    GL11.glDrawArrays(ops[base + 1], ops[base + 2], ops[base + 3]);
                }
                case OP_ENABLE -> GL11.glEnable(ops[base + 1]);
                case OP_DISABLE -> GL11.glDisable(ops[base + 1]);
                case OP_BLEND_FUNC -> GL11.glBlendFunc(ops[base + 1], ops[base + 2]);
                case OP_BIND_TEXTURE -> GL11.glBindTexture(GL11.GL_TEXTURE_2D, ops[base + 1]);
                case OP_PUSH_MATRIX -> GL11.glPushMatrix();
                case OP_POP_MATRIX -> GL11.glPopMatrix();
                case OP_LOAD_IDENTITY -> GL11.glLoadIdentity();
                case OP_TRANSLATE_F -> GL11.glTranslatef(
                        Float.intBitsToFloat(ops[base + 1]),
                        Float.intBitsToFloat(ops[base + 2]),
                        Float.intBitsToFloat(ops[base + 3]));
                case OP_ROTATE_F -> GL11.glRotatef(
                        Float.intBitsToFloat(ops[base + 1]),
                        Float.intBitsToFloat(ops[base + 2]),
                        Float.intBitsToFloat(ops[base + 3]),
                        Float.intBitsToFloat(ops[base + 4]));
                case OP_SCALE_F -> GL11.glScalef(
                        Float.intBitsToFloat(ops[base + 1]),
                        Float.intBitsToFloat(ops[base + 2]),
                        Float.intBitsToFloat(ops[base + 3]));
                case OP_MATRIX_MODE -> GL11.glMatrixMode(ops[base + 1]);
                case OP_NOOP -> {
                    // 批次内去重抵消的状态对：无 GL 调用
                }
                default -> throw new IllegalStateException("[SSOptimizer] 顶点数组批次损坏：未知操作种类 " + ops[base]);
            }
            if (OP_PROBE) {
                probeOpError(String.format("op=%d a=%d b=%d c=%d flags=%d",
                        ops[base], ops[base + 1], ops[base + 2], ops[base + 3], opFlags[i]));
            }
        }
        if (texOn) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        if (colorOn) {
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        }
        if (normOn) {
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        }
        if (vertexArrayOn) {
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        }
        syncCurrentValues();
        // 排干已执行内容（去重状态/current 快照/definedFlags 保留至串首重置）
        opCount = 0;
        segmentCount = 0;
        vertexCount = 0;
    }

    /**
     * 逐操作探针落点：取一次 glGetError，非 NO_ERROR 时记 WARN（含位置描述与错误码）。
     * 仅 {@link #OP_PROBE} 开启时被调用；调用点必须位于渲染线程（executeGl 上下文）。
     */
    private static void probeOpError(final String site) {
        if (!OP_PROBE) {
            return;
        }
        final int err = GL11.glGetError();
        if (err != org.lwjgl.opengl.GL11.GL_NO_ERROR) {
            OP_PROBE_LOG.warn(String.format("[SSOptimizer] 逐操作探针：%s 之后 GL 错误 0x%08X", site, err));
        }
    }

    /**
     * immediate 语义的 current 值回同步：把本串内最后设置的 color/texCoord/
     * normal 落到真实 GL（仅处理本串出现过的属性，未出现的属性本就不该被
     * 本回放触碰）。
     */
    private void syncCurrentValues() {
        if ((definedFlags & FLAG_COLOR) != 0) {
            GL11.glColor4ub(curR, curG, curB, curA);
        }
        if ((definedFlags & FLAG_TEX) != 0) {
            GL11.glTexCoord2f(curS, curT);
        }
        if ((definedFlags & FLAG_NORM) != 0) {
            GL11.glNormal3f(curNx, curNy, curNz);
        }
    }

    /**
     * 串内 immediate 兜底批次执行后调用：其逐指令真实 GL 调用可能改变了
     * cap/blend/纹理绑定与 current 值——去重缓存与 definedFlags 作废。
     * definedFlags 清零后，后续数组批次的未定义属性顶点走「外部当前值」
     * 语义（immediate 批次已把真实 GL current 推进到流内最新值），不会
     * 把合并器内滞留的陈旧快照烘焙进顶点数组。
     */
    void onExternalStateChange() {
        capEntryCount = 0;
        blendKnown = false;
        bindKnown = false;
        definedFlags = 0;
    }

    /**
     * 提交前把四个视图缓冲的 limit 收窄到已用区间（LWJGL2 的指针调用按
     * {@code remaining()} 取量）；收窄后的 limit 由下一批次的
     * {@link #startReplay()} 复位（{@link java.nio.Buffer#clear()}）。
     */
    void sealBuffers() {
        posBuf.limit(vertexCount * 3);
        texBuf.limit(vertexCount * 2);
        colorBuf.limit(vertexCount * 4);
        normBuf.limit(vertexCount * 3);
    }

    /** 按目标态调整客户端数组开关（仅在实际变化时产生 JNI 调用）。 */
    private static boolean switchClientState(final int array, final boolean want, final boolean current) {
        if (want == current) {
            return current;
        }
        if (want) {
            GL11.glEnableClientState(array);
        } else {
            GL11.glDisableClientState(array);
        }
        return want;
    }

    /** 测试用：当前回放操作数。 */
    int opCount() {
        return opCount;
    }

    /** 测试用：第 index 个操作的种类。 */
    int opKind(final int index) {
        return ops[index * OP_STRIDE];
    }

    /** 测试用：第 index 个操作的参数槽（0-2：a/b/c）。 */
    int opArg(final int index, final int slot) {
        return ops[index * OP_STRIDE + 1 + slot];
    }

    /** 测试用：第 index 个 DRAW 操作的段属性标志。 */
    int opDrawFlags(final int index) {
        return opFlags[index];
    }

    /** 测试用：当前累积顶点数。 */
    int vertexCount() {
        return vertexCount;
    }

    /** 测试用：第 vi 个顶点的位置分量（0-2）。 */
    float posAt(final int vi, final int component) {
        return posBuf.get(vi * 3 + component);
    }

    /** 测试用：第 vi 个顶点的 UV 分量（0-1）。 */
    float texAt(final int vi, final int component) {
        return texBuf.get(vi * 2 + component);
    }

    /** 测试用：第 vi 个顶点的颜色分量（0-3，无符号字节 0-255）。 */
    int colorAt(final int vi, final int component) {
        return colorBuf.get(vi * 4 + component) & 0xFF;
    }

    private static byte floatToUByte(final float value) {
        final float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return (byte) Math.round(clamped * 255.0f);
    }
}
