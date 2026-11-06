package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BridgeSupport.blockingGet(() -> {
                    throw new Exception("boom");
                }));
        assertNotNull(e.getCause());
    }

    @Test
    void fenceWaitFlushIsNoOpOnAuxThread() {
        BridgeSupport.flushForFenceWait();
        assertEquals(0, queue.swapCount);
    }
}
