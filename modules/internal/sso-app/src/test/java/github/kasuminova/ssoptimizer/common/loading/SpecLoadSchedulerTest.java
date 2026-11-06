package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecLoadSchedulerTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(SpecLoadScheduler.PARALLELISM_PROPERTY);
    }

    @Test
    void gateLimitsMaxConcurrencyToConfiguredParallelism() throws Exception {
        final int parallelism = 2;
        final int taskCount = parallelism * 3;
        System.setProperty(SpecLoadScheduler.PARALLELISM_PROPERTY, Integer.toString(parallelism));

        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final CountDownLatch firstTasksStarted = new CountDownLatch(parallelism);
        final CountDownLatch releaseFirstTasks = new CountDownLatch(1);

        final SpecLoadScheduler.Dag dag = SpecLoadScheduler.newDag();
        for (int i = 0; i < taskCount; i++) {
            final int taskIndex = i;
            dag.task("task-" + i, () -> {
                final int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    if (taskIndex < parallelism) {
                        firstTasksStarted.countDown();
                        if (!releaseFirstTasks.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release overlapping spec tasks");
                        }
                    }
                } finally {
                    inFlight.decrementAndGet();
                    completed.incrementAndGet();
                }
            });
        }

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<?> joining = executor.submit(() -> {
                try {
                    dag.join();
                } catch (IOException | JSONException e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                assertTrue(firstTasksStarted.await(5, TimeUnit.SECONDS),
                        "前两个任务必须确定性重叠执行");
            } finally {
                releaseFirstTasks.countDown();
            }
            joining.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstTasks.countDown();
            executor.shutdownNow();
        }

        assertEquals(taskCount, completed.get(), "全部任务必须执行完成");
        assertTrue(maxInFlight.get() >= 2,
                "并行调度必须实际重叠执行任务，实际峰值: " + maxInFlight.get());
        assertTrue(maxInFlight.get() <= parallelism,
                "并行度不得超过配置上限，实际峰值: " + maxInFlight.get());
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
    void ioExceptionFromTaskIsLoggedAndPropagatesUnwrapped() {
        final IOException failure = new IOException("disk gone");
        final Logger logger = Logger.getLogger(SpecLoadScheduler.class);
        final List<LoggingEvent> events = new CopyOnWriteArrayList<>();
        final AppenderSkeleton appender = recordingAppender(events);
        logger.addAppender(appender);

        final IOException thrown;
        try {
            thrown = assertThrows(IOException.class, () ->
                    SpecLoadScheduler.newDag()
                                     .task("failing", () -> {
                                         throw failure;
                                     })
                                     .join());
        } finally {
            logger.removeAppender(appender);
        }

        assertSame(failure, thrown, "任务体抛出的 IOException 必须在 join 处原样重抛");
        assertTrue(events.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getThrowableInformation() != null
                        && event.getThrowableInformation().getThrowable() == failure),
                "任务异常必须记录原始故障");
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

    private static AppenderSkeleton recordingAppender(final List<LoggingEvent> events) {
        return new AppenderSkeleton() {
            @Override
            protected void append(final LoggingEvent event) {
                events.add(event);
            }

            @Override
            public void close() {
            }

            @Override
            public boolean requiresLayout() {
                return false;
            }
        };
    }
}
