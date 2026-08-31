package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * aux 原生线程（{@link RecordingContext#auxNative}，经 SharedDrawable 持有真实
 * 共享 GL 上下文的模组后台线程）的 bridge choke 点旁路验证：enqueue/阻塞通道/
 * fence 等待 flush 全部直执，不触碰渲染队列，与主线程录制流完全隔离。
 */
class AuxNativeBypassTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL32.install(queue);
        BridgeSupport.recordingContext().auxNative = true;
    }

    @AfterEach
    void tearDown() {
        GL32.uninstall();
    }

    @Test
    void enqueueExecutesInlineWithoutQueueing() {
        AtomicInteger executed = new AtomicInteger();
        BridgeSupport.enqueue(executed::incrementAndGet);
        assertEquals(1, executed.get(), "aux 线程 enqueue 在调用线程立即执行");
        assertEquals(0, queue.recorded.size(), "aux 线程不产生队列命令");
        assertEquals(0, queue.frame.commandCount(), "aux 线程不污染当前帧命令列表");
    }

    @Test
    void blockingChannelsExecuteInlineWithoutQueue() throws Exception {
        int value = BridgeSupport.blockingGet(() -> 42);
        assertEquals(42, value);
        assertEquals(0, queue.getCallCount, "aux 线程阻塞取值不经队列");

        AtomicInteger ran = new AtomicInteger();
        BridgeSupport.blockingWait(ran::incrementAndGet);
        assertEquals(1, ran.get());
        assertEquals(0, queue.blockingTasks.size());

        int resource = BridgeSupport.blockingGetResource(() -> 7);
        assertEquals(7, resource);
        assertEquals(0, queue.uncountedGetCallCount);

        BridgeSupport.blockingWaitResource(ran::incrementAndGet);
        assertEquals(2, ran.get());
        assertEquals(0, queue.uncountedBlockingTasks.size());
    }

    @Test
    void inlineExecutionWrapsFailure() {
        // 内联执行的异常包装与渲染线程内同步执行同形态（不吞异常、不留空 catch）
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BridgeSupport.blockingGet(() -> {
                    throw new Exception("boom");
                }));
        assertNotNull(e.getCause());
    }

    @Test
    void fenceWaitFlushIsNoOpOnAuxThread() {
        // aux 线程无帧可切：fence 等待由真实 glClientWaitSync 承载，
        // flushForFenceWait 不得触发 swap
        BridgeSupport.flushForFenceWait();
        assertEquals(0, queue.swapCount);
    }
}
