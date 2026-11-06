package github.kasuminova.ssoptimizer.common.concurrent;

import org.apache.log4j.Logger;
import org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 基于 JCTools MPSC 队列的帧内执行器，仅限主线程投递并等待屏障。
 * 工作线程等待任务时使用二次检查避免丢失唤醒；失败任务保留现场，由主线程串行重跑一次后汇总异常。
 */
public final class FrameParallelExecutorImpl implements FrameParallelExecutor {
    private static final Logger LOGGER = Logger.getLogger(FrameParallelExecutorImpl.class);
    private static final long RERUN_WARN_INTERVAL_MS = 5_000L;

    private final String poolName;
    private final WorkerThread[] workers;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final Object completionLock = new Object();
    private final List<TaskFailure> failures = new ArrayList<>();
    private final AtomicLong lastRerunWarnMs = new AtomicLong();

    public FrameParallelExecutorImpl(final String poolName, final int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be >= 1, got " + threadCount);
        }
        this.poolName = poolName;
        workers = new WorkerThread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new WorkerThread("SSOptimizer-" + poolName + "-Worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
    }

    @Override
    public void submit(final Runnable task, final Object stripeKey) {
        int idx;
        if (stripeKey != null) {
            idx = (System.identityHashCode(stripeKey) & 0x7fffffff) % workers.length;
        } else {
            idx = (roundRobin.getAndIncrement() & 0x7fffffff) % workers.length;
        }
        pending.incrementAndGet();
        final WorkerThread worker = workers[idx];
        worker.queue.offer(task);
        // unpark 可安全早于 park 调用，offer 后的二次检查保证任务不会留在休眠队列中。
        if (worker.parked) {
            LockSupport.unpark(worker);
        }
    }

    @Override
    public void awaitAll() {
        synchronized (completionLock) {
            while (pending.get() != 0) {
                try {
                    completionLock.wait(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "[SSOptimizer] " + poolName + " awaitAll interrupted", e);
                }
            }
        }
        if (failures.isEmpty()) {
            return;
        }
        List<TaskFailure> snapshot = new ArrayList<>(failures);
        failures.clear();
        // 失败任务可能已产生部分副作用，主线程仅按失败顺序重跑一次，重跑失败则汇总抛出。
        List<Throwable> rerunFailures = new ArrayList<>(snapshot.size());
        for (TaskFailure failure : snapshot) {
            try {
                failure.task.run();
            } catch (Throwable t) {
                rerunFailures.add(t);
            }
        }
        if (!rerunFailures.isEmpty()) {
            RuntimeException propagated = new RuntimeException(
                    "[SSOptimizer] Parallel " + poolName + " failed (" + snapshot.size() + " task(s), "
                            + rerunFailures.size() + " rerun failed)", rerunFailures.get(0));
            rerunFailures.stream().skip(1).forEach(propagated::addSuppressed);
            throw propagated;
        }
        logRerunWarn(snapshot.size(), snapshot.get(0).error);
    }

    private void logRerunWarn(final int taskCount, final Throwable firstError) {
        long now = System.currentTimeMillis();
        long last = lastRerunWarnMs.get();
        if (now - last >= RERUN_WARN_INTERVAL_MS && lastRerunWarnMs.compareAndSet(last, now)) {
            LOGGER.warn("[SSOptimizer] " + taskCount + " 个并行 " + poolName + " 任务失败，主线程串行重跑成功（降级；"
                    + "首个异常摘要: " + firstError + "）");
        }
    }

    @Override
    public boolean isWorkerThread() {
        return Thread.currentThread() instanceof WorkerThread;
    }

    @Override
    public int threadCount() {
        return workers.length;
    }

    static final class TaskFailure {
        final Runnable task;
        final Throwable error;

        TaskFailure(final Runnable task, final Throwable error) {
            this.task = task;
            this.error = error;
        }
    }

    /** 空队列时先标记并复查再休眠，配合生产端 unpark 避免丢失唤醒。 */
    private final class WorkerThread extends Thread {
        private final MpscUnboundedArrayQueue<Runnable> queue = new MpscUnboundedArrayQueue<>(64);
        private volatile boolean parked;

        WorkerThread(final String name) {
            super(name);
        }

        @Override
        public void run() {
            while (true) {
                Runnable task = queue.poll();
                if (task == null) {
                    parked = true;
                    task = queue.poll();
                    if (task == null) {
                        LockSupport.park();
                        parked = false;
                        continue;
                    }
                    parked = false;
                }
                execute(task);
            }
        }

        private void execute(final Runnable task) {
            boolean success = false;
            try {
                task.run();
                success = true;
            } catch (Throwable t) {
                LOGGER.error("[SSOptimizer] " + poolName + " task failed", t);
                synchronized (completionLock) {
                    failures.add(new TaskFailure(task, t));
                }
            } finally {
                // 回收必须先于屏障计数递减，且失败任务不能回收，以免主线程重跑前字段被复用任务覆写。
                if (success && task instanceof PooledTask pooled) {
                    pooled.recycle();
                }
                if (pending.decrementAndGet() == 0) {
                    synchronized (completionLock) {
                        completionLock.notifyAll();
                    }
                }
            }
        }
    }
}
