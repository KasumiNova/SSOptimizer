package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** 仅渲染线程使用，将 immediate 顶点流合并为顶点数组和 DRAW 操作；double 载荷收窄为 float。 */
final class VertexArrayBatch implements VertexSink {
    private static final int INITIAL_VERTEX_CAPACITY = 1024;
    private static final int MAX_RETAINED_VERTICES = 1 << 20;

    private static final boolean OP_PROBE = "command".equals(System.getProperty(
            github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl.GL_ERROR_PROBE_PROPERTY, "off"));
    private static final org.apache.log4j.Logger OP_PROBE_LOG = org.apache.log4j.Logger.getLogger(VertexArrayBatch.class);

    static final int FLAG_TEX   = 1;
    static final int FLAG_COLOR = 2;
    static final int FLAG_NORM  = 4;

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

    private int[] segments = new int[SEG_STRIDE * 64];
    private int   segmentCount;
    private int[] ops = new int[OP_STRIDE * 128];
    private int[] opFlags = new int[128];
    private int   opCount;

    private int vertexCount;

    private int drawStamp;
    private int[]     capIds       = new int[8];
    private int[]     capOpIndex   = new int[8];
    private int[]     capDrawStamp = new int[8];
    private boolean[] capIsEnable  = new boolean[8];
    private int       capEntryCount;
    private boolean blendKnown;
    private int     lastBlendSrc;
    private int     lastBlendDst;
    private int     lastBlendOp;
    private int     lastBlendDrawStamp;
    private boolean bindKnown;
    private int     lastBind;
    private int     lastBindOp;
    private int     lastBindDrawStamp;

    private float curS;
    private float curT;
    private byte  curR;
    private byte  curG;
    private byte  curB;
    private byte  curA;
    private float curNx;
    private float curNy;
    private float curNz = 1.0f;
    /** 未出现的属性不启用数组，顶点沿用外部 current 值。 */
    private int   definedFlags;

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

    @Override
    public void startReplay() {
        if (vertexCapacity() > MAX_RETAINED_VERTICES) {
            allocateBuffers(INITIAL_VERTEX_CAPACITY);
        }
        // 跨批次复用前必须恢复完整 limit，绝对 put 也受 limit 约束。
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

    @Override
    public void pushMatrix() {
        flushSegmentsToOps();
        addOp(OP_PUSH_MATRIX, 0, 0, 0, 0, 0);
    }

    @Override
    public void popMatrix() {
        flushSegmentsToOps();
        addOp(OP_POP_MATRIX, 0, 0, 0, 0, 0);
    }

    @Override
    public void loadIdentity() {
        flushSegmentsToOps();
        addOp(OP_LOAD_IDENTITY, 0, 0, 0, 0, 0);
    }

    @Override
    public void translatef(final float x, final float y, final float z) {
        flushSegmentsToOps();
        addOp(OP_TRANSLATE_F, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y),
                Float.floatToRawIntBits(z), 0, 0);
    }

    @Override
    public void rotatef(final float angle, final float x, final float y, final float z) {
        flushSegmentsToOps();
        addOp(OP_ROTATE_F, Float.floatToRawIntBits(angle), Float.floatToRawIntBits(x),
                Float.floatToRawIntBits(y), Float.floatToRawIntBits(z), 0);
    }

    @Override
    public void scalef(final float x, final float y, final float z) {
        flushSegmentsToOps();
        addOp(OP_SCALE_F, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y),
                Float.floatToRawIntBits(z), 0, 0);
    }

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

    private void removeCapEntry(final int idx) {
        final int last = capEntryCount - 1;
        capIds[idx] = capIds[last];
        capOpIndex[idx] = capOpIndex[last];
        capDrawStamp[idx] = capDrawStamp[last];
        capIsEnable[idx] = capIsEnable[last];
        capEntryCount = last;
    }

    /** 属性在段中首次出现时先切分图元段，避免同段混用两种数组语义。 */
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

    private void flushSegmentsToOps() {
        closeOpenSegment();
        for (int i = 0; i < segmentCount; i++) {
            final int base = i * SEG_STRIDE;
            addOp(OP_DRAW, segments[base], segments[base + 1], segments[base + 2], 0, segments[base + 3]);
        }
        segmentCount = 0;
    }

    /** 仅合并固定顶点数且前段对齐图元边界的离散图元，连续图元一律不合并。 */
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
            // 只能跨已抵消状态合并相邻 DRAW，且不得跨矩阵操作或图元边界。
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
        // 复位 position，确保后续视图覆盖完整容量。
        to.clear();
    }

    private int vertexCapacity() {
        return posBytes.capacity() / 12;
    }

    /** 执行后恢复客户端数组状态，并同步流内最终 current 值。 */
    void executeGl() {
        if (opCount == 0) {
            syncCurrentValues();
            return;
        }
        sealBuffers();

        // client pointer 要求未绑定 ARRAY_BUFFER，批末必须恢复命令流簿记值。
        final int streamBinding = BridgeSupport.executedArrayBufferBinding();
        if (streamBinding != 0) {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
        }
        try {
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
        } finally {
            // 异常时也必须恢复绑定，避免真实状态与命令流簿记分离。
            if (streamBinding != 0) {
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, streamBinding);
            }
        }
        syncCurrentValues();
        opCount = 0;
        segmentCount = 0;
        vertexCount = 0;
    }

    private static void probeOpError(final String site) {
        if (!OP_PROBE) {
            return;
        }
        final int err = GL11.glGetError();
        if (err != org.lwjgl.opengl.GL11.GL_NO_ERROR) {
            OP_PROBE_LOG.warn(String.format("[SSOptimizer] 逐操作探针：%s 之后 GL 错误 0x%08X", site, err));
        }
    }

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

    /** 外部逐指令回放后必须作废去重缓存和属性快照。 */
    void onExternalStateChange() {
        capEntryCount = 0;
        blendKnown = false;
        bindKnown = false;
        definedFlags = 0;
    }

    void sealBuffers() {
        posBuf.limit(vertexCount * 3);
        texBuf.limit(vertexCount * 2);
        colorBuf.limit(vertexCount * 4);
        normBuf.limit(vertexCount * 3);
    }

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

    int opCount() {
        return opCount;
    }

    int opKind(final int index) {
        return ops[index * OP_STRIDE];
    }

    int opArg(final int index, final int slot) {
        return ops[index * OP_STRIDE + 1 + slot];
    }

    int opDrawFlags(final int index) {
        return opFlags[index];
    }

    int vertexCount() {
        return vertexCount;
    }

    float posAt(final int vi, final int component) {
        return posBuf.get(vi * 3 + component);
    }

    float texAt(final int vi, final int component) {
        return texBuf.get(vi * 2 + component);
    }

    int colorAt(final int vi, final int component) {
        return colorBuf.get(vi * 4 + component) & 0xFF;
    }

    private static byte floatToUByte(final float value) {
        final float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return (byte) Math.round(clamped * 255.0f);
    }
}
