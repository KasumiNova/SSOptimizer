package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * 一条 draw 命令携带的 client pointer 快照组。
 * <p>
 * 生命周期：draw 命令录制时由 {@link ClientPointerState#capture()} 产生（各快照
 * retain）；渲染线程执行 draw 时先 {@link #apply()} 全量重放 pointer 设置再执行
 * 真实 draw，finally 中 {@link #release()} 配对归还（最后一个引用该快照的 draw
 * 完成时快照缓冲回到 {@link BridgeSupport#pool()}）。
 */
final class PointerSnapshotGroup {
    private final PointerSnapshot[] snapshots;

    PointerSnapshotGroup(PointerSnapshot[] snapshots) {
        this.snapshots = snapshots;
    }

    /** 全量重放本组 pointer 设置（真实 GL 调用）。 */
    void apply() {
        for (PointerSnapshot snapshot : snapshots) {
            snapshot.apply();
        }
    }

    /** 归还本组引用的全部快照（与 capture 的 retain 配对）。 */
    void release() {
        for (PointerSnapshot snapshot : snapshots) {
            snapshot.release();
        }
    }

    /** 测试用：组内快照数。 */
    int size() {
        return snapshots.length;
    }

    /** 测试用：取下标处的快照。 */
    PointerSnapshot get(int index) {
        return snapshots[index];
    }
}
