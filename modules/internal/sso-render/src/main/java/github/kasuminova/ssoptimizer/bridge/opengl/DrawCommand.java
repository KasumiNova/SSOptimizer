package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;

import java.nio.ByteBuffer;

/**
 * 池化 draw 命令。录制线程设置参数，渲染线程执行后在 finally 中归还快照、快照组与命令对象。
 */
final class DrawCommand implements GlCommand {
    static final int DRAW_ARRAYS = 0;
    static final int DRAW_ELEMENTS_SNAPSHOT = 1;
    static final int DRAW_ELEMENTS_OFFSET = 2;
    static final int ARRAY_ELEMENT = 3;
    static final int DRAW_RANGE_ELEMENTS_SNAPSHOT = 4;
    static final int DRAW_RANGE_ELEMENTS_OFFSET = 5;

    static final int VIEW_BYTE = 0;
    static final int VIEW_INT = 1;
    static final int VIEW_SHORT = 2;

    private int variant;
    private int a;
    private int b;
    private int c;
    private long offset;
    private int d;
    private int e;
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

    void setDrawRangeElementsSnapshot(int mode, int start, int end, ByteBuffer snapshot, int view,
                                      PointerSnapshotGroup group) {
        this.variant = DRAW_RANGE_ELEMENTS_SNAPSHOT;
        this.a = mode;
        this.b = start;
        this.c = end;
        this.indexSnapshot = snapshot;
        this.indexView = view;
        this.group = group;
    }

    void setDrawRangeElementsOffset(int mode, int start, int end, int count, int type, long offset,
                                    PointerSnapshotGroup group) {
        this.variant = DRAW_RANGE_ELEMENTS_OFFSET;
        this.a = mode;
        this.b = start;
        this.c = end;
        this.d = count;
        this.e = type;
        this.offset = offset;
        this.group = group;
    }

    @Override
    public void execute() {
        try {
            if (BridgeSupport.isCompilingDisplayList() && DrawCommandImmediateDecoder.canDecode(group)) {
                // display list 只捕获客户端数组指针，池化快照必须在编译窗口内解码为按值捕获的 immediate 调用
                switch (variant) {
                    case DRAW_ARRAYS -> DrawCommandImmediateDecoder.replayDrawArrays(
                            group, a, b, c, ImmediateVertexSink.INSTANCE);
                    case DRAW_ELEMENTS_SNAPSHOT -> DrawCommandImmediateDecoder.replayDrawElements(
                            group, a, indexSnapshot, indexView, ImmediateVertexSink.INSTANCE);
                    case ARRAY_ELEMENT -> DrawCommandImmediateDecoder.replayArrayElement(
                            group, a, ImmediateVertexSink.INSTANCE);
                    default -> throw new IllegalStateException(
                            "[SSOptimizer] 编译窗口内 draw 变体不可解码：" + variant);
                }
                return;
            }
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
                case DRAW_RANGE_ELEMENTS_SNAPSHOT -> {
                    switch (indexView) {
                        case VIEW_BYTE -> org.lwjgl.opengl.GL12.glDrawRangeElements(a, b, c, indexSnapshot);
                        case VIEW_INT -> org.lwjgl.opengl.GL12.glDrawRangeElements(a, b, c, indexSnapshot.asIntBuffer());
                        case VIEW_SHORT -> org.lwjgl.opengl.GL12.glDrawRangeElements(a, b, c, indexSnapshot.asShortBuffer());
                        default -> throw new IllegalStateException("[SSOptimizer] 未知索引快照视图种类 " + indexView);
                    }
                }
                case DRAW_RANGE_ELEMENTS_OFFSET -> org.lwjgl.opengl.GL12.glDrawRangeElements(a, b, c, d, e, offset);
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

    private void clear() {
        group = null;
        indexSnapshot = null;
        offset = 0;
    }
}
