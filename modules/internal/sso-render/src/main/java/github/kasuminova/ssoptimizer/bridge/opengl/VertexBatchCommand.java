package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.MergedBatchCommand;

/** 渲染线程回放后归还顶点缓冲和命令对象。 */
final class VertexBatchCommand implements MergedBatchCommand {
    /** 仅渲染线程使用，无需同步。 */
    private static final VertexArrayBatch VERTEX_ARRAYS = new VertexArrayBatch();

    static void onExternalGlStateChange() {
        VERTEX_ARRAYS.onExternalStateChange();
    }

    private byte[] data;
    private int length;
    private boolean immediate;
    private org.jctools.queues.SpscArrayQueue<byte[]> returnTo;

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
                if (shouldDrainVertexArraysBeforeImmediate()) {
                    VERTEX_ARRAYS.finishReplay();
                    VERTEX_ARRAYS.executeGl();
                }
                VertexStream.replayBody(data, length, ImmediateVertexSink.INSTANCE);
                VERTEX_ARRAYS.onExternalStateChange();
                return;
            }
            if (runHead) {
                VERTEX_ARRAYS.startReplay();
            }
            VertexStream.replayBody(data, length, VERTEX_ARRAYS);
            if (runTail) {
                VERTEX_ARRAYS.finishReplay();
                VERTEX_ARRAYS.executeGl();
            }
        } finally {
            BridgeSupport.releaseVertexStreamBuffer(data, returnTo);
            data = null;
            returnTo = null;
            BridgeSupport.releaseVertexBatch(this);
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
