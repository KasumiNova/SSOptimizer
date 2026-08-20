package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.MergedBatchCommand;

/**
 * 池化的顶点流回放命令：持有 {@link VertexStream} 移交的字节缓冲，承载一个
 * immediate 批次的编码内容。
 * <p>
 * 录制侧：{@link BridgeSupport#flushVertexStream()} 借出本命令，把当前线程
 * 顶点流移交的缓冲接过来（{@link #setData(byte[], int, boolean)}，零拷贝）后落帧；
 * 渲染线程执行回放后（finally）先归还缓冲到
 * {@link VertexStreamBufferPool}（容量跨帧保留），再归还本命令到命令池——
 * 缓冲与命令随各自池复用，稳态零分配（相对旧 fillFrom 的逐批次
 * System.arraycopy，v36 profile 热点）。
 */
final class VertexBatchCommand implements MergedBatchCommand {
    /**
     * 渲染线程共享的顶点数组合并回放器：把整段流解码进共享直接缓冲后按
     * 图元段 {@code glDrawArrays} 提交，替代逐顶点 immediate JNI（v51
     * 渲染线程 profile：{@code VertexStream.replay} 31.6%，其中逐顶点
     * glVertex2f 为最大头）。仅渲染线程使用（帧命令的执行线程），单线程
     * 共享实例无需同步。
     * <p>
     * 串合并：帧命令列表中连续相邻的本命令实例由渲染线程识别为一个串
     * （{@link MergedBatchCommand} 协议）——串内解码共享本合并器（跨批次的
     * 冗余状态去重与相邻 DRAW 合并跨 sprite 生效），真实 GL 调用延迟到
     * 串尾一次执行。跨线程顺序约束（aux 生产者的插入命令切开串）由串边界
     * 天然保证：串内不存在任何非顶点命令。
     */
    private static final VertexArrayBatch VERTEX_ARRAYS = new VertexArrayBatch();

    /** 本命令持有的流缓冲（执行完归还缓冲池，归还后不得再引用）。 */
    private byte[] data;
    private int length;
    /**
     * 开放段切割标记（录制侧 {@link BridgeSupport#flushVertexStream} 判定）：
     * 本批次以未收口的 glBegin 段开始或结束——数组化回放无法保持「真实 GL
     * 段开放跨命令 + current 值逐指令推进」语义，整批走
     * {@link ImmediateVertexSink} 逐指令回放（罕见病态路径）。
     */
    private boolean immediate;

    /** 接管顶点流移交的缓冲。 */
    void setData(byte[] data, int length, boolean immediate) {
        this.data = data;
        this.length = length;
        this.immediate = immediate;
    }

    @Override
    public void executeMerged(final boolean runHead, final boolean runTail) {
        try {
            if (immediate) {
                // 先排干合并器中可能挂起的同串数组批次（保持执行顺序），再逐指令
                // 回放；回放的真实 GL 调用可能改变 cap/blend/纹理绑定，合并器的
                // 批次内去重缓存作废
                VERTEX_ARRAYS.finishReplay();
                VERTEX_ARRAYS.executeGl();
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
            BridgeSupport.releaseVertexStreamBuffer(data);
            data = null;
            BridgeSupport.releaseVertexBatch(this);
        }
    }

    /** 测试用：本命令承载的字节数。 */
    int length() {
        return length;
    }

    /** 测试用：开放段切割标记（immediate 兜底回放判定）。 */
    boolean immediate() {
        return immediate;
    }
}
