package github.kasuminova.ssoptimizer.common.combat.ai;

import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import github.kasuminova.ssoptimizer.common.concurrent.SharedFrameWorkers;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ParallelAiDispatcherSharedPoolTest {

    @Test
    void aiDispatcherUsesSharedPoolSingleton() {
        final FrameParallelExecutor executor = ParallelAiDispatcher.executor();
        assertNotNull(executor, "ssoptimizer.ai.parallel 默认开启，执行器不得为 null");
        assertSame(SharedFrameWorkers.get(), executor, "AI 域必须取得共享池全局单例");
    }

    @Test
    void workerThreadGuardChainRecognizesSharedWorkers() {
        assertFalse(ParallelAiDispatcher.isWorkerThread(), "主线程不得被识别为工作线程");
        final AtomicBoolean guardOnWorker = new AtomicBoolean(false);
        SharedFrameWorkers.get().submit(() -> guardOnWorker.set(ParallelAiDispatcher.isWorkerThread()), null);
        SharedFrameWorkers.get().awaitAll();
        assertTrue(guardOnWorker.get(), "共享池工作线程上守卫必须返回 true");
    }
}
