package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        GL11.glClear(0x4000);

        int value = GL11.glGetError();

        assertEquals(7, value);
        assertEquals(1, queue.swapCount, "阻塞 getter 必须先提交当前帧（drain-first）");
        assertEquals(1, queue.getCallCount);
    }

    @Test
    void blockingWaitSwapsCurrentFrameBeforeExecuting() {
        queue.getHandler = callable -> 0;
        Display.setIcon(new java.nio.ByteBuffer[]{java.nio.ByteBuffer.allocate(4)});
        assertEquals(1, queue.swapCount, "阻塞 wait/get 必须先提交当前帧（drain-first）");
    }
}
