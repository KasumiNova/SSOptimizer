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
     * <p>
     * 除本标记外，渲染线程执行序上处于 display list 编译窗口
     * （{@link BridgeSupport#isCompilingDisplayList()}）的批次同样强制逐指令
     * 回放——见 {@link #requiresImmediateReplay(boolean)}。
     */
    private boolean immediate;

    /**
     * 回放分流判定：批次是否必须走 {@link ImmediateVertexSink} 逐指令回放。
     * <p>
     * 两个触发条件任一成立即立即模式：
     * <ul>
     *   <li>{@code immediateFlag}（开放段切割，录制侧判定）；</li>
     *   <li>渲染线程正处于 display list 编译窗口——display list 编译对客户端
     *       数组按指针捕获、不回拷数据，数组化共享缓冲跨批次复用会在列表回放
     *       时读到后续批次覆盖的陈旧内容（舰船图标串图根因）；immediate 的
     *       glBegin/glVertex/glEnd 在编译期按数据捕获，回放正确。</li>
     * </ul>
     * 包内可见供单测直接验证分流判定（immediate/数组化路径内部的真实 GL 调用
     * 无法在无上下文环境执行）。
     */
    static boolean requiresImmediateReplay(final boolean immediateFlag) {
        return immediateFlag || BridgeSupport.isCompilingDisplayList();
    }

    /** 接管顶点流移交的缓冲。 */
    void setData(byte[] data, int length, boolean immediate) {
        this.data = data;
        this.length = length;
        this.immediate = immediate;
    }

    /**
     * immediate 回放前是否允许排干合并器中挂起的同串数组批次。
     * <p>
     * 开放段切割批次（窗口外，{@code immediate} 标记）需要先排干同串的数组
     * 批次再逐指令回放，保持执行顺序；display list 编译窗口内<b>严禁</b>
     * 排干——{@link VertexArrayBatch#executeGl()} 的 current 值回同步
     * （glColor4ub/glTexCoord2f）会把合并器残留的<b>陈旧</b> current 值按值捕获
     * 进列表，glCallList 重放时覆盖调用方设置的当前颜色/纹理坐标（实机症状：
     * 对话框文字阴影层级反转——主字形 pass 以阴影色重放）；数组化 glDrawArrays
     * 更会按指针捕获共享缓冲。窗口内批次（串首恒成立，glNewList 命令必然切开
     * 串）不存在同串挂起内容，跳过排干是安全且必须的。
     * <p>
     * 包内可见供单测直接验证分流判定。
     */
    static boolean shouldDrainVertexArraysBeforeImmediate() {
        return !BridgeSupport.isCompilingDisplayList();
    }

    @Override
    public void executeMerged(final boolean runHead, final boolean runTail) {
        try {
            if (requiresImmediateReplay(immediate)) {
                // 开放段切割批次（编译窗口外）：先排干合并器中可能挂起的同串数组
                // 批次（保持执行顺序），再逐指令回放；回放的真实 GL 调用可能改变
                // cap/blend/纹理绑定，合并器的批次内去重缓存作废。
                if (shouldDrainVertexArraysBeforeImmediate()) {
                    VERTEX_ARRAYS.finishReplay();
                    VERTEX_ARRAYS.executeGl();
                }
                // display list 编译窗口内（display list 按指针捕获客户端数组）与
                // 开放段切割批次同路径：immediate 调用在列表编译期按数据捕获，
                // 回放正确。窗口内跳过合并器排干——见
                // {@link #shouldDrainVertexArraysBeforeImmediate()}。
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
