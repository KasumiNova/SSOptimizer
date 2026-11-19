package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * 单个录制线程的 client pointer 状态。离散 pointer 与 interleaved 快照互斥；
 * 实例非线程安全，每个生产者线程独立持有。
 */
final class ClientPointerState {
    private PointerSnapshot vertex;
    private PointerSnapshot color;
    private PointerSnapshot texCoord;
    private PointerSnapshot normal;
    private PointerSnapshot interleaved;
    private int arrayBufferBinding;
    private static final int ATTRIB_STACK_MAX = 64;
    private final int[] attribMasks       = new int[ATTRIB_STACK_MAX];
    private final int[] attribArrayBuffer = new int[ATTRIB_STACK_MAX];
    private int attribDepth;

    int arrayBufferBinding() {
        return arrayBufferBinding;
    }

    void setArrayBufferBinding(int buffer) {
        this.arrayBufferBinding = buffer;
    }

    void pushClientAttrib(int mask) {
        if (attribDepth >= ATTRIB_STACK_MAX) {
            return;
        }
        attribMasks[attribDepth] = mask;
        attribArrayBuffer[attribDepth] = arrayBufferBinding;
        attribDepth++;
    }

    void popClientAttrib() {
        if (attribDepth <= 0) {
            return;
        }
        final int mask = attribMasks[--attribDepth];
        if ((mask & org.lwjgl.opengl.GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0) {
            arrayBufferBinding = attribArrayBuffer[attribDepth];
        }
    }

    void setVertex(PointerSnapshot snapshot) {
        releaseHeld(vertex);
        releaseHeld(interleaved);
        vertex = snapshot;
        interleaved = null;
    }

    void setColor(PointerSnapshot snapshot) {
        releaseHeld(color);
        releaseHeld(interleaved);
        color = snapshot;
        interleaved = null;
    }

    void setTexCoord(PointerSnapshot snapshot) {
        releaseHeld(texCoord);
        releaseHeld(interleaved);
        texCoord = snapshot;
        interleaved = null;
    }

    PointerSnapshot retainNormal() {
        if (normal != null) {
            normal.retain();
        }
        return normal;
    }

    void setNormal(PointerSnapshot snapshot) {
        releaseHeld(normal);
        releaseHeld(interleaved);
        normal = snapshot;
        interleaved = null;
    }

    void setInterleaved(PointerSnapshot snapshot) {
        releaseHeld(vertex);
        releaseHeld(color);
        releaseHeld(texCoord);
        releaseHeld(normal);
        releaseHeld(interleaved);
        interleaved = snapshot;
        vertex = null;
        color = null;
        texCoord = null;
        normal = null;
    }

    private static void releaseHeld(PointerSnapshot snapshot) {
        if (snapshot != null) {
            snapshot.release();
        }
    }

    /**
     * 捕获当前快照组并增加各快照的引用计数；draw 执行完后由
     * {@link PointerSnapshotGroup#release()} 配对归还。
     *
     * @return 当前快照组（全未设置时为空组）
     */
    PointerSnapshotGroup capture() {
        PointerSnapshotGroup group = BridgeSupport.acquireSnapshotGroup();
        if (interleaved != null) {
            interleaved.retain();
            group.add(interleaved);
            return group;
        }
        if (vertex != null) {
            vertex.retain();
            group.add(vertex);
        }
        if (color != null) {
            color.retain();
            group.add(color);
        }
        if (texCoord != null) {
            texCoord.retain();
            group.add(texCoord);
        }
        if (normal != null) {
            normal.retain();
            group.add(normal);
        }
        return group;
    }
}
