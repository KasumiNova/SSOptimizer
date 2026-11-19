package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.MergedBatchCommand;

import java.util.function.Consumer;

final class VertexBatchCommand implements MergedBatchCommand {
    private static final VertexArrayBatch VERTEX_ARRAYS = new VertexArrayBatch();

    static void onExternalGlStateChange() {
        VERTEX_ARRAYS.onExternalStateChange();
    }

    /** 快照由调用方独占捕获，不从顶点流池借用缓冲。 */
    static VertexBatchCommand owned(final Consumer<VertexSink> draw) {
        return new VertexBatchCommand(draw);
    }

    private final Consumer<VertexSink> owned;
    private byte[] data;
    private int length;
    private boolean immediate;
    private org.jctools.queues.SpscArrayQueue<byte[]> returnTo;

    VertexBatchCommand() {
        this(null);
    }

    private VertexBatchCommand(final Consumer<VertexSink> owned) {
        this.owned = owned;
    }

    /** 开放图元段或 display list 编译期间必须逐指令回放，避免共享顶点缓冲被按指针捕获。 */
    static boolean requiresImmediateReplay(final boolean immediateFlag) {
        return immediateFlag || BridgeSupport.isCompilingDisplayList();
    }

    void setData(byte[] data, int length, boolean immediate,
                 org.jctools.queues.SpscArrayQueue<byte[]> returnTo) {
        this.data = data;
        this.length = length;
        this.immediate = immediate;
        this.returnTo = returnTo;
    }

    /** display list 编译期间禁止排干数组合并器，避免将陈旧 current 值和共享缓冲捕获进列表。 */
    static boolean shouldDrainVertexArraysBeforeImmediate() {
        return !BridgeSupport.isCompilingDisplayList();
    }

    @Override
    public void executeMerged(final boolean runHead, final boolean runTail) {
        try {
            if (requiresImmediateReplay(immediate)) {
                if (!runHead && shouldDrainVertexArraysBeforeImmediate()) {
                    VERTEX_ARRAYS.finishReplay();
                    VERTEX_ARRAYS.executeGl();
                }
                replay(ImmediateVertexSink.INSTANCE);
                if (runTail) {
                    VERTEX_ARRAYS.onExternalStateChange();
                } else {
                    VERTEX_ARRAYS.startReplay();
                }
                return;
            }
            if (runHead) {
                VERTEX_ARRAYS.startReplay();
            }
            replay(VERTEX_ARRAYS);
            if (runTail) {
                VERTEX_ARRAYS.finishReplay();
                VERTEX_ARRAYS.executeGl();
            }
        } finally {
            if (owned == null) {
                BridgeSupport.releaseVertexStreamBuffer(data, returnTo);
                data = null;
                returnTo = null;
                BridgeSupport.releaseVertexBatch(this);
            }
        }
    }

    private void replay(final VertexSink sink) {
        if (owned == null) {
            VertexStream.replayBody(data, length, sink);
        } else {
            owned.accept(sink);
        }
    }

    int length() {
        return length;
    }

    byte[] data() {
        return data;
    }

    boolean immediate() {
        return immediate;
    }
}
