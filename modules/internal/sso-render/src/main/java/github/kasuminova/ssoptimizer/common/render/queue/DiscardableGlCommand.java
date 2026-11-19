package github.kasuminova.ssoptimizer.common.render.queue;

/** 持有一次性参数快照的命令。仅在执行或丢弃完成后方可复用实例。 */
public interface DiscardableGlCommand extends GlCommand {
    /** 执行开始后由命令在 finally 中归还资源；禁止通过 SuspendFrameException 请求重试。 */
    @Override
    void execute();

    /** 命令未执行时归还资源。可能在录制线程或渲染线程调用，不得访问 GL 上下文。 */
    void discard();
}
