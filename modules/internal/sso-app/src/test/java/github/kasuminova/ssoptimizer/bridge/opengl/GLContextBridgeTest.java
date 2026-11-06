package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GLContextBridgeTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GLContext.install(queue);
    }

    @AfterEach
    void tearDown() {
        GLContext.uninstall();
    }

    @Test
    void capabilitiesAreFetchedOnceAndCached() {
        queue.getHandler = callable -> null;
        GLContext.getCapabilities();
        GLContext.getCapabilities();
        assertEquals(1, queue.getCallCount, "capabilities 缓存后不再走阻塞通道");
    }

    @Test
    void uninstallClearsCapabilitiesCache() {
        queue.getHandler = callable -> null;
        GLContext.getCapabilities();
        GLContext.uninstall();
        GLContext.install(queue);
        GLContext.getCapabilities();
        assertEquals(2, queue.getCallCount, "uninstall 后缓存失效重新取回");
    }

    @Test
    void invalidateCapabilitiesForcesRefetch() {
        queue.getHandler = callable -> null;
        GLContext.getCapabilities();
        GLContext.invalidateCapabilities();
        GLContext.getCapabilities();
        assertEquals(2, queue.getCallCount, "上下文重建事件后缓存失效，重新走阻塞通道取回");
    }
}
