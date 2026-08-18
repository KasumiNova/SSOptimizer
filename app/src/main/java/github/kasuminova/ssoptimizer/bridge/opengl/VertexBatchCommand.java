package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;

/**
 * 池化的顶点流回放命令：持有可复用的字节缓冲，承载一个 {@link VertexStream}
 * 批次的编码内容。
 * <p>
 * 录制侧：{@link BridgeSupport#flushVertexStream()} 借出本命令，把当前线程顶点流
 * 的内容拷贝进来（{@link #fillFrom(VertexStream)}）后落帧；渲染线程执行回放后
 * （finally）归还池——缓冲随命令一起复用，稳态零分配。拷贝换缓冲区所有权留在
 * 池内（逐线程 VertexStream 的缓冲从此不移交、不重复分配）。
 */
final class VertexBatchCommand implements GlCommand {
    private byte[] data = new byte[4096];
    private int length;

    /** 把顶点流当前内容拷入本命令（缓冲不足时扩容）。 */
    void fillFrom(VertexStream stream) {
        int n = stream.length();
        if (data.length < n) {
            data = new byte[Math.max(data.length * 2, n)];
        }
        stream.copyTo(data);
        this.length = n;
    }

    @Override
    public void execute() {
        try {
            VertexStream.replay(data, length, LwjglVertexSink.INSTANCE);
        } finally {
            BridgeSupport.releaseVertexBatch(this);
        }
    }

    /** 测试用：本命令承载的字节数。 */
    int length() {
        return length;
    }
}
