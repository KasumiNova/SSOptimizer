package github.kasuminova.ssoptimizer.bridge.opengl;

/** draw 执行前重放 pointer 快照，并在结束后恢复命令流的 ARRAY_BUFFER 绑定。 */
final class PointerSnapshotGroup {
    private static final int MAX_SNAPSHOTS = 4;

    private final PointerSnapshot[] snapshots = new PointerSnapshot[MAX_SNAPSHOTS];
    private int count;

    PointerSnapshotGroup() {
    }

    void add(PointerSnapshot snapshot) {
        snapshots[count++] = snapshot;
    }

    void apply() {
        final int streamBinding = BridgeSupport.executedArrayBufferBinding();
        int current = streamBinding;
        try {
            for (int i = 0; i < count; i++) {
                PointerSnapshot snapshot = snapshots[i];
                final int required = snapshot.data == null ? snapshot.vboId : 0;
                if (current != required) {
                    org.lwjgl.opengl.GL15.glBindBuffer(
                            org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, required);
                    current = required;
                }
                snapshot.apply();
            }
        } finally {
            // 异常时也必须恢复绑定，避免真实状态与命令流簿记分离。
            if (current != streamBinding) {
                org.lwjgl.opengl.GL15.glBindBuffer(
                        org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, streamBinding);
            }
        }
    }

    void release() {
        for (int i = 0; i < count; i++) {
            snapshots[i].release();
            snapshots[i] = null;
        }
        count = 0;
        BridgeSupport.releaseSnapshotGroup(this);
    }

    int size() {
        return count;
    }

    PointerSnapshot get(int index) {
        return snapshots[index];
    }
}
