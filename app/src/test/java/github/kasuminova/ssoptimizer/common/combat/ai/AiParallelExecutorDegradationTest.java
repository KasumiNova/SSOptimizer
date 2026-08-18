package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 并行失败串行降级的契约验证：全成功零降级路径、失败任务主线程串行
 * 重跑成功（已成功任务不重跑）、重跑仍失败时重抛（不吞异常）。
 */
class AiParallelExecutorDegradationTest {

    @Test
    void allSucceedRunsEachTaskOnceWithoutDegradation() {
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(2);
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            executor.submit(runs::incrementAndGet, null);
        }
        assertDoesNotThrow(executor::awaitAll, "全成功不得触发降级/异常");
        assertEquals(8, runs.get(), "全成功路径每个任务恰执行一次");
    }

    @Test
    void failedTaskRerunsOnCallerThreadAndSucceeds() {
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(2);
        AtomicInteger runs = new AtomicInteger();
        // 首次执行抛异常（并行窗口期并发读失败），主线程串行重跑成功
        executor.submit(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new NullPointerException("simulated concurrent read failure");
            }
        }, null);
        assertDoesNotThrow(executor::awaitAll, "失败后串行重跑成功不得再抛");
        assertEquals(2, runs.get(), "失败任务必须执行（首次失败 + 主线程重跑）各一次");
    }

    @Test
    void rerunStillFailingRethrowsWithFullContext() {
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(1);
        // 每次执行都抛：重跑仍失败 → 必须重抛（禁止吞异常）
        executor.submit(() -> {
            throw new IllegalStateException("persistent failure");
        }, null);
        RuntimeException ex = assertThrows(RuntimeException.class, executor::awaitAll);
        assertTrue(String.valueOf(ex.getMessage()).contains("Parallel ship AI failed"),
                "重抛消息必须保留失败上下文");
        assertInstanceOf(IllegalStateException.class, ex.getCause(), "cause 必须是首个原始异常");
    }

    @Test
    void successfulTasksAreNotRerunWhenSiblingFails() {
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(2);
        AtomicInteger okRuns = new AtomicInteger();
        AtomicInteger failRuns = new AtomicInteger();
        executor.submit(okRuns::incrementAndGet, null);
        executor.submit(() -> {
            if (failRuns.incrementAndGet() == 1) {
                throw new RuntimeException("transient failure");
            }
        }, null);
        assertDoesNotThrow(executor::awaitAll, "失败任务重跑成功后不得抛");
        assertEquals(1, okRuns.get(), "已成功完成的任务不得重跑（同帧 AI 只能推进一次）");
        assertEquals(2, failRuns.get(), "失败任务执行（首次失败 + 主线程重跑）各一次");
    }

    @Test
    void multipleFailuresAllRerunInFailureOrder() {
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(2);
        AtomicInteger fail1 = new AtomicInteger();
        AtomicInteger fail2 = new AtomicInteger();
        executor.submit(() -> {
            if (fail1.incrementAndGet() == 1) {
                throw new RuntimeException("fail-1");
            }
        }, null);
        executor.submit(() -> {
            if (fail2.incrementAndGet() == 1) {
                throw new RuntimeException("fail-2");
            }
        }, null);
        assertDoesNotThrow(executor::awaitAll, "多个失败任务重跑成功后不得抛");
        assertEquals(2, fail1.get());
        assertEquals(2, fail2.get());
    }
}
