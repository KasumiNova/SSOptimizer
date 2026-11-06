package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

final class FakeRenderQueue implements RenderQueue {
    final List<GlCommand> recorded = new ArrayList<>();
    final List<Runnable> blockingTasks = new ArrayList<>();
    final List<Runnable> uncountedBlockingTasks = new ArrayList<>();
    int swapCount;
    int swapAndSyncCount;
    int getCallCount;
    int uncountedGetCallCount;
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
