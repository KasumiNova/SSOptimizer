package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 一条可在渲染线程执行的 GL 命令。
 * <p>
 * 动机：渲染/逻辑线程分离方案（FR 式 GL 执行迁移）要求主线程不持有 GL 上下文，
 * 所有 GL 调用在录制侧（主线程或其他生产者线程）被封成命令对象入队到当前
 * {@link RenderFrame}，帧交换后由渲染线程按提交顺序逐条执行。
 * <p>
 * 生命周期：命令在录制时构造并捕获全部参数快照（基本类型按值捕获，buffer 等
 * 可变数据由后续 BufferPool 深拷贝方案负责）；{@link #execute()} 只在渲染线程
 * 被调用一次；执行完成后命令对象及其捕获的数据允许被回收（帧归还 {@link FramePool}
 * 后不再持有引用）。
 */
@FunctionalInterface
public interface GlCommand {
    /**
     * 在渲染线程执行本命令。实现不得再访问录制侧的可变游戏状态——只允许使用
     * 构造时捕获的参数快照。
     */
    void execute();
}
