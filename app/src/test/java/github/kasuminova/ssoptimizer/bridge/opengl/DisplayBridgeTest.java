package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * bridge Display 的垂直切片验证：update() 的「swap 命令 + swapFramesAndSync」语义、
 * create() 的阻塞通道路由。
 * <p>
 * processMessages/isCloseRequested/isActive/getWidth/getHeight 是纯直通方法，
 * 执行体会加载真实 org.lwjgl.opengl.Display（其类初始化依赖 LWJL native，
 * 无显示环境抛 UnsatisfiedLinkError），无法在单测驱动，留待接入游戏后验证。
 */
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
    }

    @Test
    void updateEnqueuesSwapCommandThenSwapsAndSyncs() {
        Display.update();
        // 真实 Display.update() 被录制成一条命令（交换缓冲归渲染线程）
        assertEquals(1, queue.recorded.size());
        // 随后提交当前帧并等待上一帧（一帧流水线重叠）
        assertEquals(1, queue.swapAndSyncCount);
        assertEquals(0, queue.swapCount);
    }

    @Test
    void createRoutesThroughBlockingChannel() throws Exception {
        Display.create();
        // create 延迟到渲染线程执行：经队列的阻塞通道提交（假队列不执行命令体，
        // 真实 Display.create 在无显示环境不能调）
        assertEquals(1, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
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
        // 假队列的 wait/get 不执行命令体（真实 Display 调用在无显示环境不可调），
        // 只验证通道路由与受检异常透传结构
        Display.setDisplayMode(null);
        Display.setFullscreen(true);
        Display.destroy();
        Display.makeCurrent();
        queue.getHandler = callable -> 0;
        Display.setIcon(new java.nio.ByteBuffer[0]);
        assertEquals(4, queue.blockingTasks.size());
        assertEquals(1, queue.getCallCount, "setIcon 走阻塞取值通道");
        queue.getHandler = callable -> new org.lwjgl.opengl.DisplayMode[0];
        Display.getAvailableDisplayModes();
        queue.getHandler = callable -> null;
        Display.getDesktopDisplayMode();
        assertEquals(3, queue.getCallCount);
    }
}
