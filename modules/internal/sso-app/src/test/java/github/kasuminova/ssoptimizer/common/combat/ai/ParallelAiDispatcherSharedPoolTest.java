package github.kasuminova.ssoptimizer.common.combat.ai;

import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import github.kasuminova.ssoptimizer.common.concurrent.SharedFrameWorkers;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 调度器迁移共享池后的集成语义验证。
 * <p>
 * 覆盖：AI 域取得的执行器即 {@link SharedFrameWorkers} 全局单例（两域同一实例）、
 * {@code ssoptimizer.ai.parallel} 开关默认开启（执行器非 null）、
 * {@link ParallelAiDispatcher#isWorkerThread()} 守卫链路对共享池工作线程的识别
 * （{@code ProfilerWorkerGuardMixin} / {@code ShipSystemScriptGuardMixin} 依赖该守卫
 * 判定「SSOptimizer 工作线程不得碰游戏静态状态」）。
 */
class ParallelAiDispatcherSharedPoolTest {

    @Test
    void aiDispatcherUsesSharedPoolSingleton() {
        final FrameParallelExecutor executor = ParallelAiDispatcher.executor();
        assertNotNull(executor, "ssoptimizer.ai.parallel 默认开启，执行器不得为 null");
        assertSame(SharedFrameWorkers.get(), executor, "AI 域必须取得共享池全局单例");
    }

    @Test
    void workerThreadGuardChainRecognizesSharedWorkers() {
        // 守卫链路：ProfilerWorkerGuardMixin / ShipSystemScriptGuardMixin 经
        // ParallelAiDispatcher.isWorkerThread() 判定工作线程——共享池迁移后
        // 在共享 worker 上必须仍为 true，主线程为 false
        assertFalse(ParallelAiDispatcher.isWorkerThread(), "主线程不得被识别为工作线程");
        final AtomicBoolean guardOnWorker = new AtomicBoolean(false);
        SharedFrameWorkers.get().submit(() -> guardOnWorker.set(ParallelAiDispatcher.isWorkerThread()), null);
        SharedFrameWorkers.get().awaitAll();
        assertTrue(guardOnWorker.get(), "共享池工作线程上守卫必须返回 true");
    }
}
