package github.kasuminova.ssoptimizer.common.loading;

import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpecLoadScheduler 虚拟线程迁移（Wave 3）验证：真实 VtWorkers 执行 DAG，
 * 覆盖 Semaphore 闸门限流语义、异常类型原样传播与依赖顺序。
 */
class SpecLoadSchedulerTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(SpecLoadScheduler.PARALLELISM_PROPERTY);
    }

    @Test
    void gateLimitsMaxConcurrencyToConfiguredParallelism() throws Exception {
        System.setProperty(SpecLoadScheduler.PARALLELISM_PROPERTY, "2");

        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();

        final SpecLoadScheduler.Dag dag = SpecLoadScheduler.newDag();
        for (int i = 0; i < 6; i++) {
            dag.task("task-" + i, () -> {
                final int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    // 足够的驻留窗口保证无闸门时 6 个虚拟线程必然同时进入任务体
                    Thread.sleep(50L);
                } finally {
                    inFlight.decrementAndGet();
                    completed.incrementAndGet();
                }
            });
        }
        dag.join();

        assertEquals(6, completed.get(), "全部任务必须执行完成");
        assertEquals(2, maxInFlight.get(),
                "闸门许可数 2 时并发峰值必须为 2（无闸门时 6 个虚拟线程会全部重叠）");
    }

    @Test
    void defaultParallelismFollowsProcessorCount() {
        assertEquals(Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                SpecLoadScheduler.parallelism());
    }

    @Test
    void dependencyRunsBeforeDependent() throws Exception {
        final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

        SpecLoadScheduler.newDag()
                         .task("first", () -> order.add("first"))
                         .task("second", () -> order.add("second"), "first")
                         .join();

        assertEquals(List.of("first", "second"), List.copyOf(order));
    }

    @Test
    void ioExceptionFromTaskPropagatesUnwrapped() {
        final IOException failure = new IOException("disk gone");

        final IOException thrown = assertThrows(IOException.class, () ->
                SpecLoadScheduler.newDag()
                                 .task("failing", () -> {
                                     throw failure;
                                 })
                                 .join());

        assertSame(failure, thrown, "任务体抛出的 IOException 必须在 join 处原样重抛");
    }

    @Test
    void jsonExceptionFromTaskPropagatesUnwrapped() {
        final JSONException failure = new JSONException("bad json");

        final JSONException thrown = assertThrows(JSONException.class, () ->
                SpecLoadScheduler.newDag()
                                 .task("failing", () -> {
                                     throw failure;
                                 })
                                 .join());

        assertSame(failure, thrown, "任务体抛出的 JSONException 必须在 join 处原样重抛");
    }

    @Test
    void runtimeExceptionFromTaskPropagatesUnwrapped() {
        final IllegalStateException failure = new IllegalStateException("boom");

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                SpecLoadScheduler.newDag()
                                 .task("failing", () -> {
                                     throw failure;
                                 })
                                 .join());

        assertSame(failure, thrown, "任务体抛出的 RuntimeException 必须在 join 处原样重抛");
    }

    @Test
    void dependencyOnUnregisteredTaskRejected() {
        final SpecLoadScheduler.Dag dag = SpecLoadScheduler.newDag()
                .task("orphan", () -> {
                }, "missing");

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, dag::join);
        assertTrue(thrown.getMessage().contains("missing"));
    }
}
