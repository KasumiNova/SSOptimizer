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

/**
 * 市场推进「降频 + 并行」组合语义验证。
 * <p>
 * 覆盖：并行关闭与 Wave 6 降频内联逐帧等价、降频过滤后并行执行且 dt 不丢不重、
 * 屏障次序（分发 → 全部完成 → 返回）、玩家市场主线程内联（跨市场共享写保持
 * 原版线程与循环序）、失败任务屏障处主线程串行重跑降级。
 */
class MarketAdvanceParallelDispatcherTest {
    private static final float DT = 0.1F;

    @Test
    void disabledParallelIsFrameByFrameIdenticalToWave6Throttle() {
        // 并行关闭（executor=null）时，NPC 与玩家市场的推进序列必须与
        // 纯降频 helper 完全一致——零行为变化
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
        // interval=3，7 帧：推进发生在第 1（0.1）、3（0.2）、6（0.3）帧，第 7 帧挂起
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
        // dt 不丢不重：已转发 + 待推进 == 总请求
        double forwarded = 0.0;
        for (final float dt : npc.advances) {
            forwarded += dt;
        }
        assertEquals(7.0 * DT, forwarded + npc.pending, 1e-6);
        // NPC 市场必须在工作线程执行
        assertTrue(npc.threads.stream().allMatch(t -> t.startsWith("SSOptimizer-Econ-Worker-")));
    }

    @Test
    void playerMarketAdvancesInlineOnCallerThreadInLoopOrder() {
        // 玩家市场：dispatch 返回前已完成推进（内联），且相对顺序保持循环序——
        // SharedData 月报 / marketShareData / 构建事件三处共享写的原版语义依赖这一点
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

        // 玩家市场内联：dispatch 返回即完成，且在调用线程
        assertEquals(List.of("player1", "player2"), mainThreadEvents, "玩家市场必须按循环序内联推进");
        assertEquals(List.of(caller), player1.threads);
        assertEquals(List.of(caller), player2.threads);

        MarketAdvanceParallelDispatcher.awaitAll(executor);
        assertEquals(1, npc1.advances.size());
        assertEquals(1, npc2.advances.size());
    }

    @Test
    void barrierWaitsForAllParallelMarketsBeforeReturning() {
        // 屏障次序：stepper 段（主线程）→ 并行推进全部完成 → awaitAll 返回。
        // 4 个市场任务互相等待对方全部开始（2 工作线程下若串行执行必然超时），
        // 证明真并行；且 awaitAll 返回时全部推进已完成。
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
        // 工作线程任务异常：屏障处主线程串行重跑一次（同帧推进两次的单帧降级），不抛
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
        // 重跑仍失败：屏障重抛（禁止吞异常），帧内中断交由上层处理
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
        // 降频语义保持：新市场首次出现当帧即推进（不滞后到第 N 帧），并行下亦然
        final FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("Econ", 2);
        final RecordingBridge fresh = new RecordingBridge("fresh");

        MarketAdvanceParallelDispatcher.dispatch(executor, fresh, false, DT, 5);
        MarketAdvanceParallelDispatcher.awaitAll(executor);

        assertEquals(List.of(DT), fresh.advances);
        assertEquals(0.0, fresh.pending);
    }

    /**
     * 桥接记录桩：状态字段与 Mixin 注入字段同构，记录每次真实推进的 dt 与执行线程。
     */
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
