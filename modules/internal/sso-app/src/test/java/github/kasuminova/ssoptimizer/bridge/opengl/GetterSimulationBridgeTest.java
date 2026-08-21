package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * getter 仿真的桥接路由验证：glIsProgram/glIsTexture/glGetTexLevelParameteri/
 * caps 常量在主录制线程上命中本地簿记，完全不触阻塞通道。
 * <p>
 * 与 {@link GetterChannelTest} 的分工：那边验证阻塞通道本身的语义（真实渲染线程），
 * 本类验证「仿真命中时不进入阻塞通道」（FakeRenderQueue 计数断言）。
 */
class GetterSimulationBridgeTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL11.install(queue);
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
        RenderThreadMode.resetLoadingFinishedForTesting();
    }

    @Test
    void isProgramAnswersFromBookkeepingWithoutBlockingChannel() {
        // glCreateProgram 走资源通道取回真实 id 并登记簿记
        queue.getHandler = callable -> 42;
        final int program = GL20.glCreateProgram();
        assertEquals(42, program);
        assertEquals(1, queue.uncountedGetCallCount);

        // 簿记命中：不新增任何阻塞通道调用
        assertTrue(GL20.glIsProgram(42));
        assertFalse(GL20.glIsProgram(99), "未创建的名字必须为 false");
        assertEquals(0, queue.getCallCount, "isProgram 仿真命中不得进入计数阻塞通道");

        // 删除（非当前 program）后立即失效
        GL20.glDeleteProgram(42);
        assertFalse(GL20.glIsProgram(42));
        assertEquals(0, queue.getCallCount);
    }

    @Test
    void deleteInUseProgramStaysValidUntilUnbound() {
        queue.getHandler = callable -> 42;
        final int program = GL20.glCreateProgram();
        GL20.glUseProgram(program);

        GL20.glDeleteProgram(program);
        assertTrue(GL20.glIsProgram(program), "使用中删除按 GL 规范延迟销毁");

        GL20.glUseProgram(0);
        assertFalse(GL20.glIsProgram(program), "解绑后删除生效");
        assertEquals(0, queue.getCallCount);
    }

    @Test
    void isTextureAnswersFromBookkeepingWithoutBlockingChannel() {
        queue.getHandler = callable -> 7;
        final int texture = GL11.glGenTextures();
        assertEquals(1, queue.uncountedGetCallCount);

        assertTrue(GL11.glIsTexture(texture));
        assertFalse(GL11.glIsTexture(8));
        assertEquals(0, queue.getCallCount, "isTexture 仿真命中不得进入计数阻塞通道");

        GL11.glDeleteTextures(texture);
        assertFalse(GL11.glIsTexture(texture));
    }

    @Test
    void texLevelParamsAnsweredLocallyAfterUpload() {
        queue.getHandler = callable -> 7;
        final int texture = GL11.glGenTextures();
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                org.lwjgl.opengl.GL11.GL_RGBA, 64, 32, 0,
                org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);

        assertEquals(64, GL11.glGetTexLevelParameteri(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH));
        assertEquals(32, GL11.glGetTexLevelParameteri(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT));
        assertEquals(0, queue.getCallCount, "已簿记纹理的 level-0 尺寸查询不得阻塞");

        // 未跟踪 level 回退阻塞通道
        queue.getHandler = callable -> 16;
        assertEquals(16, GL11.glGetTexLevelParameteri(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 1, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH));
        assertEquals(1, queue.getCallCount, "未簿记的查询必须回退阻塞通道");
    }

    @Test
    void capConstantsAreCachedAfterFirstReadback() {
        queue.getHandler = callable -> 16384;
        assertEquals(16384, GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE));
        assertEquals(1, queue.getCallCount, "首次读回走一次阻塞通道");

        assertEquals(16384, GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE));
        assertEquals(1, queue.getCallCount, "caps 常量第二次起命中缓存，零阻塞");
    }
}
