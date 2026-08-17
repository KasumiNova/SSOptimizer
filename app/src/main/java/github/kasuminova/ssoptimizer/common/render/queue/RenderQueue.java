package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.concurrent.Callable;

/**
 * 渲染线程命令队列：FR 式 GL 执行迁移的核心执行器。
 * <p>
 * 架构定调（见 docs/design/render-logic-separation-entrypoints.md）：主线程保留
 * 原版主循环与全部游戏状态读写，所有 GL 调用录制为 {@link GlCommand} 入队到当前
 * {@link RenderFrame}；唯一持有 GL 上下文的渲染线程（SSOptimizer-Render）按提交
 * 顺序执行命令。同步模型为双缓冲命令帧 + 一帧流水线重叠：
 * {@link #swapFramesAndSync()} 提交第 N 帧后只等待第 N-1 帧完成。
 * <p>
 * 多生产者：除主线程外，BoxUtil 类模组的自家线程（aux-context 钩子的结构基础）
 * 也会经 {@link #submit(GlCommand)} 入队，提交通道必须线程安全。
 */
public interface RenderQueue {
    /**
     * 主线程快速通道：取当前录制帧直接追加命令。仅限主线程使用；
     * 其他生产者线程必须走 {@link #submit(GlCommand)}。
     *
     * @return 当前录制帧
     */
    RenderFrame currentFrame();

    /**
     * 多生产者录制通道（线程安全）：把命令追加到当前录制帧。
     * 跨线程的命令先后顺序不保证，GPU 可见性协调用 {@link FrameFence}。
     *
     * @param command 待录制命令
     */
    void submit(GlCommand command);

    /**
     * 提交当前帧到渲染线程，并从帧池取新帧作为当前录制帧。不阻塞。
     */
    void swapFrames();

    /**
     * 提交当前帧 + 只等待<strong>上一帧</strong>执行完成（一帧流水线重叠，
     * 参考 FR Executor.swapFramesAndSync）。若上一帧在渲染线程执行时抛过异常，
     * 在此向主线程重抛（{@link IllegalStateException}，cause 为原始异常）。
     */
    void swapFramesAndSync();

    /**
     * 阻塞式同步执行（getter 回读的兜底通道）：把 callable 交给渲染线程执行并
     * 阻塞等待结果。每次调用计入 {@link StallDetector}——这是全管线 drain，
     * 仅限低频/未仿真路径使用。渲染线程上调用时直接执行（避免自死锁）。
     *
     * @param getter 要在渲染线程执行的取值逻辑
     * @param <T>    返回值类型
     * @return getter 在渲染线程的执行结果
     */
    <T> T get(Callable<T> getter);

    /**
     * {@link #get(Callable)} 的无返回值形式。
     *
     * @param task 要在渲染线程执行的逻辑
     */
    void wait(Runnable task);

    /**
     * @return 当前线程是否为本队列的渲染线程
     */
    boolean isRenderThread();
}
