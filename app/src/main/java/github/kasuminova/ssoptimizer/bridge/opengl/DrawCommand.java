package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;

import java.nio.ByteBuffer;

/**
 * 池化的 draw 命令（glDrawArrays/glDrawElements/glArrayElement 全变体）。
 * <p>
 * 动机：draw 是逐 sprite 的高频命令，原实现每条 draw 分配「draw lambda +
 * 包装 lambda」两个命令对象；池化后录制侧借出、渲染线程执行完（finally）
 * 归还，稳态零分配。生命周期安全性见 {@link CommandPool} 的 javadoc。
 * <p>
 * 语义与原 lambda 实现完全一致：执行时先 {@link PointerSnapshotGroup#apply()}
 * 重放录制时刻捕获的 pointer 快照组，再执行真实 draw；finally 中归还快照组、
 * 索引快照（buffer 变体）与本命令对象。
 */
final class DrawCommand implements GlCommand {
    /** glDrawArrays(mode, first, count)。 */
    static final int DRAW_ARRAYS = 0;
    /** glDrawElements(mode, indices快照)：索引 buffer 录制时刻深拷贝，执行后归还池。 */
    static final int DRAW_ELEMENTS_SNAPSHOT = 1;
    /** glDrawElements(mode, count, type, offset)：VBO 索引偏移形式，无 buffer 快照。 */
    static final int DRAW_ELEMENTS_OFFSET = 2;
    /** glArrayElement(index)。 */
    static final int ARRAY_ELEMENT = 3;

    /** 索引快照的视图种类：ByteBuffer 原样。 */
    static final int VIEW_BYTE = 0;
    /** 索引快照的视图种类：IntBuffer 视图。 */
    static final int VIEW_INT = 1;
    /** 索引快照的视图种类：ShortBuffer 视图。 */
    static final int VIEW_SHORT = 2;

    private int variant;
    /** 复用字段：DRAW_ARRAYS 为 mode/first/count；DRAW_ELEMENTS_* 为 mode/count/type；ARRAY_ELEMENT 为 index。 */
    private int a;
    private int b;
    private int c;
    /** DRAW_ELEMENTS_OFFSET 的索引偏移。 */
    private long offset;
    /** DRAW_ELEMENTS_SNAPSHOT 的池化索引快照与其视图种类。 */
    private ByteBuffer indexSnapshot;
    private int indexView;
    private PointerSnapshotGroup group;

    void setDrawArrays(int mode, int first, int count, PointerSnapshotGroup group) {
        this.variant = DRAW_ARRAYS;
        this.a = mode;
        this.b = first;
        this.c = count;
        this.group = group;
    }

    void setDrawElementsSnapshot(int mode, ByteBuffer snapshot, int view, PointerSnapshotGroup group) {
        this.variant = DRAW_ELEMENTS_SNAPSHOT;
        this.a = mode;
        this.indexSnapshot = snapshot;
        this.indexView = view;
        this.group = group;
    }

    void setDrawElementsOffset(int mode, int count, int type, long offset, PointerSnapshotGroup group) {
        this.variant = DRAW_ELEMENTS_OFFSET;
        this.a = mode;
        this.b = count;
        this.c = type;
        this.offset = offset;
        this.group = group;
    }

    void setArrayElement(int index, PointerSnapshotGroup group) {
        this.variant = ARRAY_ELEMENT;
        this.a = index;
        this.group = group;
    }

    @Override
    public void execute() {
        try {
            group.apply();
            switch (variant) {
                case DRAW_ARRAYS -> org.lwjgl.opengl.GL11.glDrawArrays(a, b, c);
                case DRAW_ELEMENTS_SNAPSHOT -> {
                    switch (indexView) {
                        case VIEW_BYTE -> org.lwjgl.opengl.GL11.glDrawElements(a, indexSnapshot);
                        case VIEW_INT -> org.lwjgl.opengl.GL11.glDrawElements(a, indexSnapshot.asIntBuffer());
                        case VIEW_SHORT -> org.lwjgl.opengl.GL11.glDrawElements(a, indexSnapshot.asShortBuffer());
                        default -> throw new IllegalStateException("[SSOptimizer] 未知索引快照视图种类 " + indexView);
                    }
                }
                case DRAW_ELEMENTS_OFFSET -> org.lwjgl.opengl.GL11.glDrawElements(a, b, c, offset);
                case ARRAY_ELEMENT -> org.lwjgl.opengl.GL11.glArrayElement(a);
                default -> throw new IllegalStateException("[SSOptimizer] 未知 draw 命令变体 " + variant);
            }
        } finally {
            group.release();
            if (indexSnapshot != null) {
                BridgeSupport.releaseSnapshot(indexSnapshot);
            }
            clear();
            BridgeSupport.releaseDrawCommand(this);
        }
    }

    /** 归还池前清空引用，避免池内对象滞留快照/快照组的堆引用。 */
    private void clear() {
        group = null;
        indexSnapshot = null;
        offset = 0;
    }
}
