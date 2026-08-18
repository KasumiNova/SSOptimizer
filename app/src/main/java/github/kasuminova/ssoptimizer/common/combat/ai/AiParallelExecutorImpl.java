package github.kasuminova.ssoptimizer.common.combat.ai;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 并行执行线程池的默认实现。
 * <p>
 * 线程模型：固定数量工作线程各自持有一个无界队列，主线程（游戏逻辑线程）通过
 * {@link #submit(Runnable, Object)} 投递任务并以 {@link #awaitAll()} 作为帧内屏障。
 * 屏障不跨帧：每帧的 AI 任务必须在同一帧内全部完成。
 * <p>
 * 分组键语义：携带相同 stripeKey 的任务按身份哈希固定到同一工作线程，保证串行
 * （战机编队共享 {@code FighterWing} 状态，同编队战机 AI 不得并行）；无键任务
 * 按轮询分发以获得最大并行度。
 */
public final class AiParallelExecutorImpl implements AiParallelExecutor {
    private static final Logger LOGGER = Logger.getLogger(AiParallelExecutorImpl.class);
    /** 重跑降级 warn 的限频间隔（毫秒）：并行竞态持续时汇总报告，防止日志洪泛。 */
    private static final long RERUN_WARN_INTERVAL_MS = 5_000L;

    private final WorkerThread[] workers;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final Object completionLock = new Object();
    /** 失败任务记录（任务 + 异常）：awaitAll 降级时在主线程串行重跑失败任务。 */
    private final List<TaskFailure> failures = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong lastRerunWarnMs = new java.util.concurrent.atomic.AtomicLong();

    /**
     * 创建线程池并立即启动工作线程（daemon）。
     *
     * @param threadCount 工作线程数，至少为 1
     */
    public AiParallelExecutorImpl(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be >= 1, got " + threadCount);
        }
        workers = new WorkerThread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new WorkerThread("SSOptimizer-AI-Worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
    }

    @Override
    public void submit(Runnable task, Object stripeKey) {
        int idx;
        if (stripeKey != null) {
            idx = (System.identityHashCode(stripeKey) & 0x7fffffff) % workers.length;
        } else {
            idx = (roundRobin.getAndIncrement() & 0x7fffffff) % workers.length;
        }
        pending.incrementAndGet();
        workers[idx].queue.offer(task);
    }

    @Override
    public void awaitAll() {
        synchronized (completionLock) {
            while (pending.get() != 0) {
                try {
                    completionLock.wait(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("[SSOptimizer] AI awaitAll interrupted", e);
                }
            }
        }
        if (failures.isEmpty()) {
            return;
        }
        List<TaskFailure> snapshot = new ArrayList<>(failures);
        failures.clear();
        // 串行降级：失败任务在主线程（awaitAll 调用线程）按失败顺序重跑一次——
        // 已成功完成的任务不在 failures 中，天然不重跑。失败多为并行窗口期的
        // 并发读（NPE/CME），主线程串行重跑消除并发后通常成功；任务闭包
        // （Ship AI advance）非幂等，重跑 = 同帧内该 AI 推进两次，但相比
        // 崩溃（CombatEngine.advanceInner 帧内中断）是可接受的单帧降级。
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
                    "[SSOptimizer] Parallel ship AI failed (" + snapshot.size() + " task(s), "
                            + rerunFailures.size() + " rerun failed)", rerunFailures.get(0));
            rerunFailures.stream().skip(1).forEach(propagated::addSuppressed);
            throw propagated;
        }
        // 重跑全部成功：限频 warn 汇总（任务数与首个异常摘要）
        logRerunWarn(snapshot.size(), snapshot.get(0).error);
    }

    /** 限频记录串行重跑降级（并行任务失败但主线程重跑成功）。 */
    private void logRerunWarn(int taskCount, Throwable firstError) {
        long now = System.currentTimeMillis();
        long last = lastRerunWarnMs.get();
        if (now - last >= RERUN_WARN_INTERVAL_MS && lastRerunWarnMs.compareAndSet(last, now)) {
            LOGGER.warn("[SSOptimizer] " + taskCount + " 个并行 AI 任务失败，主线程串行重跑成功（降级；"
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

    /** 失败任务记录：任务闭包与其原始异常（降级重跑与诊断用）。 */
    static final class TaskFailure {
        final Runnable task;
        final Throwable error;

        TaskFailure(Runnable task, Throwable error) {
            this.task = task;
            this.error = error;
        }
    }

    /**
     * AI 工作线程。任务异常必须记录（任务 + 异常）并上报到主线程屏障降级，
     * 禁止静默吞没。
     */
    private final class WorkerThread extends Thread {
        private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

        WorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            while (true) {
                Runnable task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    LOGGER.warn("[SSOptimizer] AI worker interrupted, continuing", e);
                    continue;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.error("[SSOptimizer] Ship AI task failed", t);
                    synchronized (completionLock) {
                        failures.add(new TaskFailure(task, t));
                    }
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        synchronized (completionLock) {
                            completionLock.notifyAll();
                        }
                    }
                }
            }
        }
    }
}
