package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * bridge GL11 的录制行为验证：每个静态方法把调用封成一条 GlCommand 入队到
 * 注入的 RenderQueue（假消费者只录制不执行——命令体是真实 GL 调用，无上下文
 * 环境不可执行；命令体的参数正确性由接入游戏后的截图验证兜底）。
 */
class GL11BridgeTest {

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
    void immediateAndMatrixCallsAreRecordedInOrder() {
        GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
        GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glPushMatrix();
        GL11.glTranslatef(1f, 2f, 0f);
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f);
        GL11.glColor4ub((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        GL11.glVertex2f(3f, 4f);
        GL11.glEnd();
        GL11.glPopMatrix();
        assertEquals(11, queue.recorded.size());
        // 只录制不执行：无 GL 上下文也未抛异常，证明调用被完整延迟
        assertEquals(0, queue.swapCount);
        assertEquals(0, queue.swapAndSyncCount);
    }

    @Test
    void stateCallsAreRecordedOneCommandEach() {
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glViewport(0, 0, 800, 600);
        GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glRotatef(90f, 0f, 0f, 1f);
        GL11.glScalef(2f, 2f, 1f);
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.glOrtho(0, 800, 600, 0, -1, 1);
        GL11.glFlush();
        GL11.glFinish();
        assertEquals(11, queue.recorded.size());
    }

    @Test
    void throwsWhenQueueNotInstalled() {
        GL11.uninstall();
        assertThrows(IllegalStateException.class, () -> GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT));
    }
}
