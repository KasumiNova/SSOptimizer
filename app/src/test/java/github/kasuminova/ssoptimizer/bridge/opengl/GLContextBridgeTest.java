package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GLContext bridge 的缓存语义验证：getCapabilities 首次走阻塞通道、此后读缓存。
 * 真实 ContextCapabilities 依赖 GL 上下文无法构造，桩返回 null——这里验证的是
 * 通道路由与缓存计数（非空路径由接入游戏后的运行验证兜底）。
 */
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
}
