package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;

/**
 * 池化的顶点流回放命令：持有 {@link VertexStream} 移交的字节缓冲，承载一个
 * immediate 批次的编码内容。
 * <p>
 * 录制侧：{@link BridgeSupport#flushVertexStream()} 借出本命令，把当前线程
 * 顶点流移交的缓冲接过来（{@link #setData(byte[], int)}，零拷贝）后落帧；
 * 渲染线程执行回放后（finally）先归还缓冲到
 * {@link VertexStreamBufferPool}（容量跨帧保留），再归还本命令到命令池——
 * 缓冲与命令随各自池复用，稳态零分配（相对旧 fillFrom 的逐批次
 * System.arraycopy，v36 profile 热点）。
 */
final class VertexBatchCommand implements GlCommand {
    /** 本命令持有的流缓冲（执行完归还缓冲池，归还后不得再引用）。 */
    private byte[] data;
    private int length;

    /** 接管顶点流移交的缓冲。 */
    void setData(byte[] data, int length) {
        this.data = data;
        this.length = length;
    }

    @Override
    public void execute() {
        try {
            VertexStream.replay(data, length, LwjglVertexSink.INSTANCE);
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
}
