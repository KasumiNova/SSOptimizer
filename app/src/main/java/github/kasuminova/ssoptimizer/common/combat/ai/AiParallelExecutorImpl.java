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

    private final WorkerThread[] workers;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final Object completionLock = new Object();
    private final List<Throwable> failures = new ArrayList<>();

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
        if (!failures.isEmpty()) {
            List<Throwable> snapshot = new ArrayList<>(failures);
            failures.clear();
            RuntimeException propagated = new RuntimeException(
                    "[SSOptimizer] Parallel ship AI failed (" + snapshot.size() + " task(s))", snapshot.get(0));
            snapshot.stream().skip(1).forEach(propagated::addSuppressed);
            throw propagated;
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

    /**
     * AI 工作线程。任务异常必须记录并上报到主线程屏障，禁止静默吞没。
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
                        failures.add(t);
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
