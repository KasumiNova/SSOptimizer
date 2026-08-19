package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * 录制侧（单个生产者线程）的 client pointer 当前状态。
 * <p>
 * 职责：保存最近一次 glVertexPointer/glColorPointer/glTexCoordPointer/
 * glNormalPointer/glInterleavedArrays 的快照，供 draw 命令
 * （glDrawArrays/glDrawElements/glArrayElement）在录制时刻整体捕获。
 * <p>
 * 简化语义（与真实 GL 的差异，javadoc 于 {@link PointerSnapshot} 亦有说明）：
 * 真实 GL 中 glInterleavedArrays 与离散 pointer 可以逐数组互相覆盖；本阶段
 * 简化为「设置 interleaved 使离散快照失效，设置任一离散 pointer 使 interleaved
 * 失效」——游戏的实际调用序列不依赖两者混用的细分覆盖，后续随
 * ClientAttribTracker 式仿真补全。
 * <p>
 * 非线程安全：每个生产者线程持有独立实例（{@link BridgeSupport#pointerState()}
 * 的 ThreadLocal）。
 */
final class ClientPointerState {
    private PointerSnapshot vertex;
    private PointerSnapshot color;
    private PointerSnapshot texCoord;
    private PointerSnapshot normal;
    private PointerSnapshot interleaved;
    /** 录制侧跟踪的 GL_ARRAY_BUFFER 绑定（offset 指针重放时恢复用，见 PointerSnapshotGroup.apply）。 */
    private int arrayBufferBinding;

    int arrayBufferBinding() {
        return arrayBufferBinding;
    }

    void setArrayBufferBinding(int buffer) {
        this.arrayBufferBinding = buffer;
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

    /** 释放状态对旧快照的持有（与被捕获 draw 的引用计数配对，见 PointerSnapshot）。 */
    private static void releaseHeld(PointerSnapshot snapshot) {
        if (snapshot != null) {
            snapshot.release();
        }
    }

    /**
     * 捕获当前快照组供一条 draw 命令携带：每个非 null 快照 {@link PointerSnapshot#retain()}
     * 一次，draw 执行完后由 {@link PointerSnapshotGroup#release()} 配对归还。
     * 组对象从命令池借出（{@link BridgeSupport#acquireSnapshotGroup()}），
     * release 时归还。
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
