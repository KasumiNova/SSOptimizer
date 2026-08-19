package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * 测试用的假命令消费者：录制 submit 的命令而不执行（命令体是真实 GL 调用，
 * 无上下文环境不能执行），从而验证 bridge 的录制行为。
 * <p>
 * 阻塞通道：{@link #wait(Runnable)} 只记录不执行（执行体会触碰真实 Display/GL，
 * 无显示环境不可调）；{@link #get(Callable)} 不执行 callable，转而返回可注入的
 * {@link #getHandler} 的结果（默认抛 UnsupportedOperationException——用例不关心
 * 阻塞取值时保持旧行为，关心时注入桩返回值验证路由与计数）。
 */
final class FakeRenderQueue implements RenderQueue {
    final List<GlCommand> recorded = new ArrayList<>();
    final List<Runnable> blockingTasks = new ArrayList<>();
    /** 资源申请类（不计数）阻塞 wait 通道的记录。 */
    final List<Runnable> uncountedBlockingTasks = new ArrayList<>();
    int swapCount;
    int swapAndSyncCount;
    int getCallCount;
    /** 资源申请类（不计数）阻塞取值通道的调用次数。 */
    int uncountedGetCallCount;
    /** get 通道的桩：入参是（不会被执行的）真实 GL 取值 callable，出参是桩返回值。 */
    Function<Callable<?>, Object> getHandler = callable -> {
        throw new UnsupportedOperationException("fake queue 不支持 get");
    };
    final RenderFrame frame = new RenderFrame();

    @Override
    public RenderFrame currentFrame() {
        return frame;
    }

    @Override
    public void submit(GlCommand command) {
        recorded.add(command);
        // 同步真实队列的「提交序号递增」语义：状态命令去重的相邻性判据
        // （StateDedup 依赖 frame.commitSeq）依赖每次提交都进帧命令列表
        frame.add(command);
    }

    @Override
    public void swapFrames() {
        swapCount++;
    }

    @Override
    public void swapFramesAndSync() {
        swapAndSyncCount++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Callable<T> getter) {
        getCallCount++;
        return (T) getHandler.apply(getter);
    }

    @Override
    public void wait(Runnable task) {
        // 只记录不执行：执行体会触碰真实 Display（无显示环境）
        blockingTasks.add(task);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getUncounted(Callable<T> getter) {
        uncountedGetCallCount++;
        return (T) getHandler.apply(getter);
    }

    @Override
    public void waitUncounted(Runnable task) {
        uncountedBlockingTasks.add(task);
    }

    @Override
    public boolean isRenderThread() {
        return false;
    }
}
