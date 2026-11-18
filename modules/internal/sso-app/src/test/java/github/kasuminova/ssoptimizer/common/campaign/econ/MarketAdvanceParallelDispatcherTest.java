package github.kasuminova.ssoptimizer.common.campaign.econ;

import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutorImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MarketAdvanceParallelDispatcherTest {
    private static final float DT = 0.1F;

    @Test
    void disabledParallelIsFrameByFrameIdenticalToWave6Throttle() {
        for (final boolean playerOwned : new boolean[]{false, true}) {
            final RecordingBridge viaDispatcher = new RecordingBridge("m");
            final RecordingBridge viaHelper = new RecordingBridge("m");
            for (int frame = 0; frame < 9; frame++) {
                MarketAdvanceParallelDispatcher.dispatch(null, viaDispatcher, playerOwned, DT, 3);
                MarketAdvanceThrottleHelper.advanceThrottled(viaHelper, DT, 3);
            }
            assertEquals(viaHelper.advances, viaDispatcher.advances,
                    "playerOwned=" + playerOwned + " 时推进序列必须逐帧等价");
            assertEquals(viaHelper.pending, viaDispatcher.pending, 1e-9);
            assertTrue(viaDispatcher.threads.stream().allMatch(
                    t -> t.equals(Thread.currentThread().getName())), "关闭时不得离开调用线程");
        }
    }

    @Test
    void throttledFilterThenParallelExecutionKeepsDtExact() {
        final FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("Econ", 2);
        final RecordingBridge npc = new RecordingBridge("npc");

        for (int frame = 0; frame < 7; frame++) {
            final int advancesBefore = npc.advances.size();
            MarketAdvanceParallelDispatcher.dispatch(executor, npc, false, DT, 3);
            MarketAdvanceParallelDispatcher.awaitAll(executor);
            final int submitted = npc.advances.size() - advancesBefore;
            final boolean dueFrame = frame == 0 || (frame + 1) % 3 == 0;
            assertEquals(dueFrame ? 1 : 0, submitted, "第 " + (frame + 1) + " 帧推进次数");
        }

        assertEquals(3, npc.advances.size());
        assertEquals(DT, npc.advances.get(0), 1e-7F);
        assertEquals(2.0 * DT, npc.advances.get(1), 1e-6F);
        assertEquals(3.0 * DT, npc.advances.get(2), 1e-6F);
        double forwarded = 0.0;
        for (final float dt : npc.advances) {
            forwarded += dt;
        }
        assertEquals(7.0 * DT, forwarded + npc.pending, 1e-6);
        assertTrue(npc.threads.stream().allMatch(t -> t.startsWith("SSOptimizer-Econ-Worker-")));
    }

    @Test
    void playerMarketAdvancesInlineOnCallerThreadInLoopOrder() {
        final FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("Econ", 2);
        final String caller = Thread.currentThread().getName();
        final RecordingBridge npc1 = new RecordingBridge("npc1");
        final RecordingBridge player1 = new RecordingBridge("player1");
        final RecordingBridge npc2 = new RecordingBridge("npc2");
        final RecordingBridge player2 = new RecordingBridge("player2");
        final List<String> mainThreadEvents = new CopyOnWriteArrayList<>();

        MarketAdvanceParallelDispatcher.dispatch(executor, npc1, false, DT, 1);
        MarketAdvanceParallelDispatcher.dispatch(executor, player1, true, DT, 1);
        mainThreadEvents.addAll(player1.advances.isEmpty() ? List.of() : List.of("player1"));
        MarketAdvanceParallelDispatcher.dispatch(executor, npc2, false, DT, 1);
        MarketAdvanceParallelDispatcher.dispatch(executor, player2, true, DT, 1);
        mainThreadEvents.addAll(player2.advances.isEmpty() ? List.of() : List.of("player2"));

        assertEquals(List.of("player1", "player2"), mainThreadEvents, "玩家市场必须按循环序内联推进");
        assertEquals(List.of(caller), player1.threads);
        assertEquals(List.of(caller), player2.threads);

        MarketAdvanceParallelDispatcher.awaitAll(executor);
        assertEquals(1, npc1.advances.size());
        assertEquals(1, npc2.advances.size());
    }

    @Test
    void barrierWaitsForAllParallelMarketsBeforeReturning() {
        final FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("Econ", 2);
        final int markets = 4;
        final CountDownLatch entered = new CountDownLatch(markets);
        final AtomicInteger completed = new AtomicInteger();
        final List<String> order = new CopyOnWriteArrayList<>();

        order.add("stepper");
        for (int i = 0; i < markets; i++) {
            final RecordingBridge bridge = new RecordingBridge("npc" + i) {
                @Override
                public void ssoptimizer$advanceNow(final float amount) {
                    super.ssoptimizer$advanceNow(amount);
                    entered.countDown();
                    try {
                        assertTrue(entered.await(10, TimeUnit.SECONDS), "并行任务必须并发执行");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fail("interrupted");
                    }
                    completed.incrementAndGet();
                }
            };
            MarketAdvanceParallelDispatcher.dispatch(executor, bridge, false, DT, 1);
        }
        MarketAdvanceParallelDispatcher.awaitAll(executor);
        order.add("returned");

        assertEquals(markets, completed.get(), "屏障返回前全部市场推进必须完成");
        assertEquals(List.of("stepper", "returned"), order, "stepper 先于并行、返回晚于全部完成");
    }

    @Test
    void workerFailureRerunsSeriallyOnCallerThread() {
        final FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("Econ", 2);
        final String caller = Thread.currentThread().getName();
        final AtomicInteger runs = new AtomicInteger();
        final List<String> runThreads = new CopyOnWriteArrayList<>();
        final RecordingBridge flaky = new RecordingBridge("flaky") {
            @Override
            public void ssoptimizer$advanceNow(final float amount) {
                runThreads.add(Thread.currentThread().getName());
                if (runs.incrementAndGet() == 1) {
                    throw new IllegalStateException("simulated concurrent read failure");
                }
                super.ssoptimizer$advanceNow(amount);
            }
        };

        MarketAdvanceParallelDispatcher.dispatch(executor, flaky, false, DT, 1);
        assertDoesNotThrow(() -> MarketAdvanceParallelDispatcher.awaitAll(executor));

        assertEquals(2, runs.get(), "失败任务必须执行（worker 首次失败 + 主线程重跑）各一次");
        assertEquals(1, flaky.advances.size(), "重跑成功后恰好完成一次真实推进");
        assertTrue(runThreads.get(0).startsWith("SSOptimizer-Econ-Worker-"));
        assertEquals(caller, runThreads.get(1), "重跑必须发生在 awaitAll 调用线程");
    }

    @Test
    void persistentFailureRethrowsAtBarrier() {
        final FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("Econ", 1);
        final RecordingBridge broken = new RecordingBridge("broken") {
            @Override
            public void ssoptimizer$advanceNow(final float amount) {
                throw new IllegalStateException("persistent failure");
            }
        };
        MarketAdvanceParallelDispatcher.dispatch(executor, broken, false, DT, 1);
        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> MarketAdvanceParallelDispatcher.awaitAll(executor));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void newMarketAdvancesImmediatelyUnderParallel() {
        final FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("Econ", 2);
        final RecordingBridge fresh = new RecordingBridge("fresh");

        MarketAdvanceParallelDispatcher.dispatch(executor, fresh, false, DT, 5);
        MarketAdvanceParallelDispatcher.awaitAll(executor);

        assertEquals(List.of(DT), fresh.advances);
        assertEquals(0.0, fresh.pending);
    }

    private static class RecordingBridge implements MarketAdvanceThrottleBridge {
        final List<Float> advances = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final String name;
        volatile double pending;
        volatile int callCount;

        RecordingBridge(final String name) {
            this.name = name;
        }

        @Override
        public double ssoptimizer$getPendingAdvanceSeconds() {
            return pending;
        }

        @Override
        public void ssoptimizer$setPendingAdvanceSeconds(final double seconds) {
            pending = seconds;
        }

        @Override
        public int ssoptimizer$getAdvanceCallCount() {
            return callCount;
        }

        @Override
        public void ssoptimizer$setAdvanceCallCount(final int count) {
            callCount = count;
        }

        @Override
        public void ssoptimizer$advanceNow(final float amount) {
            threads.add(Thread.currentThread().getName());
            advances.add(amount);
        }
    }
}
