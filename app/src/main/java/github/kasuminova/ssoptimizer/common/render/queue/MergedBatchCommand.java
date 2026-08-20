package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 可在渲染线程上跨连续实例合并执行的批次命令（immediate 顶点批次）。
 * <p>
 * 动机：录制侧为保证跨线程命令顺序，immediate 顶点流在 glEnd 处即落帧——
 * 连续 sprite 在帧命令列表中形成一串相邻的顶点批次命令。渲染线程把同实现的
 * 连续实例识别为一个「串」：串内解码共享同一个合并器（跨批次的冗余状态指令
 * 去重、相邻 DRAW 合并为一次 glDrawArrays），真实 GL 调用延迟到串尾一次执行。
 * <p>
 * 串边界由 {@link RenderQueueImpl} 判定：帧命令列表中相邻且
 * {@code getClass()} 相同的实例属于同一串（不同实现互不错误链接）。
 */
public interface MergedBatchCommand extends GlCommand {
    /**
     * 以串成员身份执行。
     *
     * @param runHead 本命令是串首：重置合并器的全部跨批次累积状态
     * @param runTail 本命令是串尾：收口累积数据并执行整串的真实 GL 调用
     */
    void executeMerged(boolean runHead, boolean runTail);

    /** 独立执行（非串协议路径，如测试直调）：等价为单命令串。 */
    @Override
    default void execute() {
        executeMerged(true, true);
    }
}
