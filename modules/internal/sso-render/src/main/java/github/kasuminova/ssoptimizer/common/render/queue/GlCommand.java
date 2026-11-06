package github.kasuminova.ssoptimizer.common.render.queue;

@FunctionalInterface
public interface GlCommand {
    /** 仅在渲染线程执行一次，且只能读取构造时捕获的参数快照。 */
    void execute();
}
