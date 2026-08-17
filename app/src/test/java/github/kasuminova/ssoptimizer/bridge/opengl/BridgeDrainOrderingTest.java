package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * bridge 阻塞通道的 drain-first 顺序语义验证（见 BridgeSupport.blockingGet javadoc）：
 * 阻塞调用必须先把当前录制帧提交进渲染线程（swapFrames），再走阻塞通道——
 * 保证 getter/阻塞任务读到的是「此前全部已录制命令执行完」的 GL 状态。
 */
class BridgeDrainOrderingTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL11.install(queue);
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
    }

    @Test
    void blockingGetterSwapsCurrentFrameBeforeQuerying() {
        queue.getHandler = callable -> 7;
        GL11.glClear(0x4000); // 录制一条普通命令，阻塞 getter 前必须先把它提交出去

        int value = GL11.glGetError();

        assertEquals(7, value);
        assertEquals(1, queue.swapCount, "阻塞 getter 必须先提交当前帧（drain-first）");
        assertEquals(1, queue.getCallCount);
    }

    @Test
    void blockingWaitSwapsCurrentFrameBeforeExecuting() {
        // Display.setIcon 走阻塞通道（blockingGet 形态），drain-first 语义与 getter 一致
        queue.getHandler = callable -> 0;
        Display.setIcon(new java.nio.ByteBuffer[]{java.nio.ByteBuffer.allocate(4)});
        assertEquals(1, queue.swapCount, "阻塞 wait/get 必须先提交当前帧（drain-first）");
    }
}
