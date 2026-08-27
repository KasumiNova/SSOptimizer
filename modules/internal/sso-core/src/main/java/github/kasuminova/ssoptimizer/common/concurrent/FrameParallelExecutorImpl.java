package github.kasuminova.ssoptimizer.common.concurrent;

import org.apache.log4j.Logger;
import org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 帧内并行任务执行器的默认实现。
 * <p>
 * 线程模型：固定数量工作线程各自持有一个 JCTools MPSC 无界数组队列（生产者
 * 仅主线程分发循环，消费者仅本线程），主线程（游戏逻辑线程）通过
 * {@link #submit(Runnable, Object)} 投递任务并以 {@link #awaitAll()} 作为帧内屏障。
 * <p>
 * 队列选型：相对 LinkedBlockingQueue 消除了逐任务的 Node 分配与双锁
 * （cpu profile：submit 556 样本中 92% 为 LinkedBlockingQueue.offer）；
 * 空队列等待走「置 parked 标记 → 二次 poll → park」协议，生产端 offer 后
 * 见标记即 unpark，无丢失唤醒（顺序论证见 {@link WorkerThread#run()}）。
 * <p>
 * 分组键语义：携带相同 stripeKey 的任务按身份哈希固定到同一工作线程，保证串行
 * （如战机编队共享 {@code FighterWing} 状态，同编队战机 AI 不得并行）；无键任务
 * 按轮询分发以获得最大并行度。
 * <p>
 * 失败降级：任务异常记录（任务 + 异常）并在屏障处由主线程串行重跑一次；
 * 任务闭包一般非幂等，重跑 = 同帧内该任务推进两次，但相比崩溃（主线程帧内中断）
 * 是可接受的单帧降级。重跑仍失败则汇总重抛，不吞异常。
 */
public final class FrameParallelExecutorImpl implements FrameParallelExecutor {
    private static final Logger LOGGER = Logger.getLogger(FrameParallelExecutorImpl.class);
    /** 重跑降级 warn 的限频间隔（毫秒）：并行竞态持续时汇总报告，防止日志洪泛。 */
    private static final long RERUN_WARN_INTERVAL_MS = 5_000L;

    /** 池名：用于工作线程命名与日志/异常消息区分池用途（如 AI / Econ）。 */
    private final String poolName;
    private final WorkerThread[] workers;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final Object completionLock = new Object();
    /** 失败任务记录（任务 + 异常）：awaitAll 降级时在主线程串行重跑失败任务。 */
    private final List<TaskFailure> failures = new ArrayList<>();
    private final AtomicLong lastRerunWarnMs = new AtomicLong();

    /**
     * 创建线程池并立即启动工作线程（daemon）。
     *
     * @param poolName    池名（工作线程名 {@code SSOptimizer-<poolName>-Worker-N}）
     * @param threadCount 工作线程数，至少为 1
     */
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
        // 唤醒协议生产端：offer 先于 parked 读；若读到 true 则 worker 尚未 park
        // 或已 park——unpark 对未 park 线程无副作用（其后的 park 立即返回）
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
        // 串行降级：失败任务在主线程（awaitAll 调用线程）按失败顺序重跑一次——
        // 已成功完成的任务不在 failures 中，天然不重跑。失败多为并行窗口期的
        // 并发读（NPE/CME），主线程串行重跑消除并发后通常成功；任务闭包非幂等，
        // 重跑 = 同帧内该任务推进两次，但相比崩溃是可接受的单帧降级。
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
        // 重跑全部成功：限频 warn 汇总（任务数与首个异常摘要）
        logRerunWarn(snapshot.size(), snapshot.get(0).error);
    }

    /** 限频记录串行重跑降级（并行任务失败但主线程重跑成功）。 */
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

    /** 失败任务记录：任务闭包与其原始异常（降级重跑与诊断用）。 */
    static final class TaskFailure {
        final Runnable task;
        final Throwable error;

        TaskFailure(final Runnable task, final Throwable error) {
            this.task = task;
            this.error = error;
        }
    }

    /**
     * 工作线程。任务异常必须记录（任务 + 异常）并上报到主线程屏障降级，
     * 禁止静默吞没。
     * <p>
     * 空转等待协议（防丢失唤醒）：消费者 poll 到空 → 置 {@link #parked} →
     * 二次 poll → 仍空才 park。生产端 offer 后读 {@link #parked}：若读到
     * false，则 offer 先行发生于消费者二次 poll 之前（volatile 顺序），
     * 二次 poll 必见任务不会入睡；若读到 true 则 unpark 兜底。
     */
    private final class WorkerThread extends Thread {
        /** 任务队列：MPSC 无界数组队列（生产者：主线程分发循环；消费者：本线程）。 */
        private final MpscUnboundedArrayQueue<Runnable> queue = new MpscUnboundedArrayQueue<>(64);
        /** park 标记（生产端据此决定 unpark）；仅本线程写、生产线程读。 */
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

        /** 执行单个任务：异常记录到 failures 待降级重跑；成功的池化任务归还池。 */
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
                // 归还必须先于屏障计数递减：保证 awaitAll 返回时成功任务已全部回池，
                // 主线程下一帧分发能立即复用（稳态零分配）。仅成功任务归还：
                // 失败任务被 failures 引用等待主线程重跑，归还后字段会被下次
                // acquire 覆写
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
