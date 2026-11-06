package github.kasuminova.ssoptimizer.common.render.queue;

/** 同实现的相邻命令合并执行；runHead 重置累积状态，runTail 收口并提交真实 GL 调用。 */
public interface MergedBatchCommand extends GlCommand {
    void executeMerged(boolean runHead, boolean runTail);

    @Override
    default void execute() {
        executeMerged(true, true);
    }
}
