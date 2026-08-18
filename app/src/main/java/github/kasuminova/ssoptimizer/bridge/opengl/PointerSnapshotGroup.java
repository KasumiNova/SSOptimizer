package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * 一条 draw 命令携带的 client pointer 快照组（池化对象，经
 * {@link BridgeSupport#acquireSnapshotGroup()} 借出）。
 * <p>
 * 生命周期：draw 命令录制时由 {@link ClientPointerState#capture()} 产生（各快照
 * retain）；渲染线程执行 draw 时先 {@link #apply()} 全量重放 pointer 设置再执行
 * 真实 draw，finally 中 {@link #release()} 配对归还（最后一个引用该快照的 draw
 * 完成时快照缓冲回到 {@link BridgeSupport#pool()}，组对象本身回到命令池）。
 * <p>
 * 池化说明：组内快照至多 4 个（VERTEX/COLOR/TEX_COORD/NORMAL 各一，或单个
 * INTERLEAVED），固定容量数组免除逐 draw 的数组分配；归还时清空引用避免
 * 池内对象滞留堆。
 */
final class PointerSnapshotGroup {
    /** 组内快照上限：四个离散 pointer 各一，或单个 interleaved。 */
    private static final int MAX_SNAPSHOTS = 4;

    private final PointerSnapshot[] snapshots = new PointerSnapshot[MAX_SNAPSHOTS];
    private int count;

    /** 池化对象构造入口：{@link BridgeSupport#acquireSnapshotGroup()}。 */
    PointerSnapshotGroup() {
    }

    /** 追加一个快照（capture 时调用，快照的 retain 由调用方负责）。 */
    void add(PointerSnapshot snapshot) {
        snapshots[count++] = snapshot;
    }

    /**
     * 全量重放本组 pointer 设置（真实 GL 调用）。
     * <p>
     * ARRAY_BUFFER 绑定编排：真实 GL 在 pointer 调用时刻捕获绑定（VBO 偏移形式要求
     * 绑定点有效，buffer 形式要求无绑定），而本 bridge 把 pointer 重放推迟到 draw
     * 执行——模组在设完 pointer 后改动绑定再 draw 是合法序列（LazyFont：绑定 VBO →
     * 设 offset pointer → 解绑 → draw），重放时必须逐快照恢复录制时刻的绑定。
     * 「命令流执行到这儿的真实绑定」从 {@link BridgeSupport#executedArrayBufferBinding()}
     * 读取（渲染线程侧簿记，bind 命令执行时同步），避免每次 draw 一次 glGetInteger
     * 往返；重放结束后恢复到该值，保证后续命令看到的状态与未分离时一致。
     */
    void apply() {
        final int streamBinding = BridgeSupport.executedArrayBufferBinding();
        int current = streamBinding;
        for (int i = 0; i < count; i++) {
            PointerSnapshot snapshot = snapshots[i];
            // buffer 形式（含 INTERLEAVED）要求未绑定；偏移形式要求绑定录制时刻的 VBO
            final int required = snapshot.data == null ? snapshot.vboId : 0;
            if (current != required) {
                org.lwjgl.opengl.GL15.glBindBuffer(
                        org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, required);
                current = required;
            }
            snapshot.apply();
        }
        if (current != streamBinding) {
            org.lwjgl.opengl.GL15.glBindBuffer(
                    org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, streamBinding);
        }
    }

    /** 归还本组引用的全部快照（与 capture 的 retain 配对），组对象归还命令池。 */
    void release() {
        for (int i = 0; i < count; i++) {
            snapshots[i].release();
            snapshots[i] = null;
        }
        count = 0;
        BridgeSupport.releaseSnapshotGroup(this);
    }

    /** 测试用：组内快照数。 */
    int size() {
        return count;
    }

    /** 测试用：取下标处的快照。 */
    PointerSnapshot get(int index) {
        return snapshots[index];
    }
}
