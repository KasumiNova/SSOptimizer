package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 录制侧只编码 CPU 数据，渲染线程执行 native 绘制。
 * 不得同时实现 {@link MergedBatchCommand}，也不得在 display list 编译窗口入队。
 */
public interface NativeRenderCommand extends GlCommand {
}
