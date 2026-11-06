package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    void updateEnqueuesSwapCommandThenSwapsAndSyncs() {
        Display.update();
        assertEquals(1, queue.recorded.size());
        assertEquals(1, queue.swapAndSyncCount);
        assertEquals(0, queue.swapCount);
    }

    @Test
    void createRoutesThroughBlockingChannel() throws Exception {
        Display.create();
        assertEquals(1, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
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

    @Test
    void updateThrowsWhenQueueNotInstalled() {
        Display.uninstall();
        assertThrows(IllegalStateException.class, Display::update);
    }

    @Test
    void windowAttributeChangesAreEnqueued() throws Exception {
        Display.setVSyncEnabled(true);
        Display.setTitle("title");
        Display.setLocation(10, 20);
        assertEquals(3, queue.recorded.size(), "窗口属性变更按普通命令入队");
        assertEquals(0, queue.blockingTasks.size());
    }

    @Test
    void windowLevelChangesRouteThroughBlockingChannel() throws Exception {
        Display.setDisplayMode(null);
        Display.setFullscreen(true);
        Display.destroy();
        Display.makeCurrent();
        queue.getHandler = callable -> 0;
        Display.setIcon(new java.nio.ByteBuffer[0]);
        assertEquals(4, queue.blockingTasks.size());
        assertEquals(1, queue.uncountedGetCallCount, "setIcon 归资源申请类，走不计数阻塞取值通道");
        assertEquals(0, queue.getCallCount);
        queue.getHandler = callable -> new org.lwjgl.opengl.DisplayMode[0];
        Display.getAvailableDisplayModes();
        queue.getHandler = callable -> null;
        Display.getDesktopDisplayMode();
        assertEquals(2, queue.getCallCount, "显示模式查询属回读类，保持计数");
    }
}
