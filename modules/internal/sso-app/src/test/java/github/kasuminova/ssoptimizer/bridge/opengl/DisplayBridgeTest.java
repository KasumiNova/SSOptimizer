package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayBridgeTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        Display.install(queue);
    }

    @AfterEach
    void tearDown() {
        Display.uninstall();
        GLContext.uninstall();
    }

    @Test
    void contextRecreationInvalidatesCapabilitiesCacheAndBufferStash() throws Exception {
        GLContext.install(queue);
        queue.getHandler = callable -> null;
        GLContext.getCapabilities();
        assertEquals(1, queue.getCallCount);
        queue.getHandler = callable -> new int[BridgeSupport.BUFFER_ID_STASH_BATCH];
        BridgeSupport.acquireBufferId();
        assertEquals(1, queue.uncountedGetCallCount);

        Display.create();

        queue.getHandler = callable -> null;
        GLContext.getCapabilities();
        assertEquals(2, queue.getCallCount, "create 后 capabilities 缓存必须失效");
        queue.getHandler = callable -> new int[BridgeSupport.BUFFER_ID_STASH_BATCH];
        BridgeSupport.acquireBufferId();
        assertEquals(2, queue.uncountedGetCallCount, "create 后 stash 旧 id 必须清空");
    }
}
