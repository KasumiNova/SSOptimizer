package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并行段绑定的 bridge 路由语义：worker 绑定段后一切录制（普通命令、顶点流
 * 落帧、状态命令）绕过帧临界区直写绑定段；段内状态去重以绑定段为相邻性判据；
 * 绑定期间阻塞式调用 fail-fast。
 */
class SegmentBindingTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        BridgeSupport.install(queue);
        BridgeSupport.stateDedupEnabled = true;
    }

    @AfterEach
    void tearDown() {
        BridgeSupport.uninstall();
    }

    @Test
    void boundWorkerCommandsBypassQueueSubmit() throws Exception {
        int base = queue.frame.reserveSegments(1);
        RenderSegment assigned = queue.frame.segment(base);

        runOnWorker(() -> {
            BridgeSupport.bindSegment(assigned);
            try {
                BridgeSupport.enqueue(() -> {
                });
                BridgeSupport.enqueue(() -> {
                });
            } finally {
                BridgeSupport.unbindSegment();
            }
        });

        assertTrue(queue.recorded.isEmpty(), "绑定段期间录制不得经过帧临界区（queue.submit）");
        assertEquals(2, queue.frame.commandCount(), "命令必须落在指派段内");
        assertEquals(2, assigned.commandCount());
    }

    @Test
    void boundWorkerStateDedupIsIsolatedPerSegment() throws Exception {
        int base = queue.frame.reserveSegments(1);
        RenderSegment assigned = queue.frame.segment(base);

        runOnWorker(() -> {
            BridgeSupport.bindSegment(assigned);
            try {
                BridgeSupport.enqueueState(StateDedup.TYPE_ENABLE, 1, 0, 0, 0, () -> {
                });
                BridgeSupport.enqueueState(StateDedup.TYPE_ENABLE, 1, 0, 0, 0, () -> {
                });
            } finally {
                BridgeSupport.unbindSegment();
            }
        });

        assertEquals(1, assigned.commandCount(), "段内紧邻的相同状态命令必须去重");

        // 主线程（未绑定）录制同样的状态命令：跨段边界不去重，必须照常入队
        BridgeSupport.enqueueState(StateDedup.TYPE_ENABLE, 1, 0, 0, 0, () -> {
        });
        assertEquals(1, queue.recorded.size(), "段边界必须打断去重（跨段状态不可假设）");
    }

    @Test
    void blockingCallsFailFastInsideBoundSegment() throws Exception {
        int base = queue.frame.reserveSegments(1);
        RenderSegment assigned = queue.frame.segment(base);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            BridgeSupport.bindSegment(assigned);
            try {
                assertBlockingRejected(() -> BridgeSupport.blockingGet(() -> 1), "blockingGet");
                assertBlockingRejected(() -> BridgeSupport.blockingWait(() -> {
                }), "blockingWait");
                assertBlockingRejected(() -> BridgeSupport.blockingGetResource(() -> 1), "blockingGetResource");
                assertBlockingRejected(() -> BridgeSupport.blockingWaitResource(() -> {
                }), "blockingWaitResource");
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                BridgeSupport.unbindSegment();
            }
        });
        worker.start();
        worker.join();

        assertNull(failure.get(), "四类阻塞通道在绑定段内都必须 fail-fast");
        assertEquals(0, queue.getCallCount, "fail-fast 必须先于任何 drain/提交动作");
        assertEquals(0, queue.uncountedGetCallCount);
        assertEquals(0, queue.swapCount, "fail-fast 不得触发帧 swap");
    }

    @Test
    void unboundWorkerStillRoutesThroughQueue() throws Exception {
        runOnWorker(() -> BridgeSupport.enqueue(() -> {
        }));

        assertEquals(1, queue.recorded.size(), "未绑定的线程保持原有 queue.submit 路由");
    }

    private static void assertBlockingRejected(Runnable call, String name) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, call::run,
                name + " 在并行段内必须被拒绝");
        assertTrue(thrown.getMessage().contains("并行录制段内禁止"), name + " 的异常消息应指明分段不变量");
    }

    /** 在独立线程上运行并 join（worker 上下文与主线程隔离；异常向外传播）。 */
    private static void runOnWorker(ThrowingRunnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        worker.start();
        worker.join();
        assertNull(failure.get(), "worker 线程执行不得失败");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
