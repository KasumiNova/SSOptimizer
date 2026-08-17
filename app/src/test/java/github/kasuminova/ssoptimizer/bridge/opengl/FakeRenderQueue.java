package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 测试用的假命令消费者：录制 submit 的命令而不执行（命令体是真实 GL 调用，
 * 无上下文环境不能执行），从而验证 bridge 的录制行为。
 */
final class FakeRenderQueue implements RenderQueue {
    final List<GlCommand> recorded = new ArrayList<>();
    final List<Runnable> blockingTasks = new ArrayList<>();
    int swapCount;
    int swapAndSyncCount;
    private final RenderFrame frame = new RenderFrame();

    @Override
    public RenderFrame currentFrame() {
        return frame;
    }

    @Override
    public void submit(GlCommand command) {
        recorded.add(command);
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
    public <T> T get(Callable<T> getter) {
        throw new UnsupportedOperationException("fake queue 不支持 get");
    }

    @Override
    public void wait(Runnable task) {
        // 只记录不执行：执行体会触碰真实 Display（无显示环境）
        blockingTasks.add(task);
    }

    @Override
    public boolean isRenderThread() {
        return false;
    }
}
