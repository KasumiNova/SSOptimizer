package github.kasuminova.ssoptimizer.common.concurrent;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedFrameWorkersTest {

    @Test
    void singletonReturnsSameInstance() {
        assertSame(SharedFrameWorkers.get(), SharedFrameWorkers.get(), "共享池必须全局唯一");
    }

    @Test
    void allTasksCompleteWithoutExceedingWorkerCount() {
        final FrameParallelExecutor executor = SharedFrameWorkers.get();
        final int taskCount = executor.threadCount() * 2;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();

        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                final int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    Thread.yield();
                } finally {
                    inFlight.decrementAndGet();
                    completed.incrementAndGet();
                }
            }, null);
        }
        executor.awaitAll();

        assertEquals(taskCount, completed.get(), "全部任务必须执行完成");
        assertTrue(maxInFlight.get() <= executor.threadCount(),
                "并行度不得超过工作线程数，实际峰值: " + maxInFlight.get());
    }

    @Test
    void sameStripeKeyRunsSeriallyOnOneWorker() {
        final FrameParallelExecutor executor = SharedFrameWorkers.get();
        final int taskCount = executor.threadCount() + 1;
        final Object key = new Object();
        final ConcurrentLinkedQueue<Thread> keyedThreads = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> keyedThreads.add(Thread.currentThread()), key);
        }
        executor.awaitAll();

        assertEquals(taskCount, keyedThreads.size(), "分组任务必须全部执行");
        assertEquals(1, keyedThreads.stream().distinct().count(), "相同 stripeKey 必须在同一 worker 串行");
    }

    @Test
    void persistentTaskFailureIsLoggedAndPropagated() {
        final FrameParallelExecutor executor = SharedFrameWorkers.get();
        final IllegalStateException failure = new IllegalStateException("persistent failure");
        final Logger logger = Logger.getLogger(FrameParallelExecutorImpl.class);
        final List<LoggingEvent> events = new CopyOnWriteArrayList<>();
        final AppenderSkeleton appender = recordingAppender(events);
        logger.addAppender(appender);

        final RuntimeException propagated;
        try {
            executor.submit(() -> {
                throw failure;
            }, null);
            propagated = assertThrows(RuntimeException.class, executor::awaitAll);
        } finally {
            logger.removeAppender(appender);
        }

        assertSame(failure, propagated.getCause(), "调用方必须收到原始任务异常");
        assertTrue(events.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getThrowableInformation() != null
                        && event.getThrowableInformation().getThrowable() == failure),
                "工作线程必须记录原始任务异常");
    }

    @Test
    void isWorkerThreadGuardRecognizesSharedWorkers() {
        assertFalse(SharedFrameWorkers.isWorkerThread(), "主线程不得被识别为工作线程");
        final AtomicBoolean onWorker = new AtomicBoolean(false);
        SharedFrameWorkers.get().submit(() -> onWorker.set(SharedFrameWorkers.isWorkerThread()), null);
        SharedFrameWorkers.get().awaitAll();
        assertTrue(onWorker.get(), "共享池工作线程必须被守卫识别");
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
