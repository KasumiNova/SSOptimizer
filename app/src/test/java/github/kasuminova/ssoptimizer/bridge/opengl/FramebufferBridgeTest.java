package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FBO 三族（EXTFramebufferObject/ARBFramebufferObject/GL30）bridge 的录制行为
 * 抽查：三者是同功能别名，逐类验证命令录制与阻塞通道路由。
 */
class FramebufferBridgeTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        BridgeSupport.install(queue);
    }

    @AfterEach
    void tearDown() {
        BridgeSupport.uninstall();
    }

    @Test
    void extFramebufferCallsAreRecordedAndGensAreBlocking() {
        queue.getHandler = callable -> 3;
        assertEquals(3, EXTFramebufferObject.glGenFramebuffersEXT());
        assertEquals(3, EXTFramebufferObject.glGenRenderbuffersEXT());
        queue.getHandler = callable -> org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT;
        assertEquals(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT,
                EXTFramebufferObject.glCheckFramebufferStatusEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT));
        assertEquals(3, queue.uncountedGetCallCount, "gen/完整性校验归资源申请类不计数");
        assertEquals(0, queue.getCallCount, "资源申请类不得触碰计数通道");

        EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 3);
        EXTFramebufferObject.glFramebufferTexture2DEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                org.lwjgl.opengl.EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 7, 0);
        EXTFramebufferObject.glBindRenderbufferEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_RENDERBUFFER_EXT, 3);
        EXTFramebufferObject.glRenderbufferStorageEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_RENDERBUFFER_EXT,
                org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, 800, 600);
        EXTFramebufferObject.glFramebufferRenderbufferEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                org.lwjgl.opengl.EXTFramebufferObject.GL_DEPTH_ATTACHMENT_EXT,
                org.lwjgl.opengl.EXTFramebufferObject.GL_RENDERBUFFER_EXT, 3);
        EXTFramebufferObject.glGenerateMipmapEXT(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        EXTFramebufferObject.glDeleteFramebuffersEXT(3);
        EXTFramebufferObject.glDeleteRenderbuffersEXT(3);
        assertEquals(8, queue.recorded.size());
    }

    @Test
    void arbFramebufferCallsAreRecordedAndGensAreBlocking() {
        queue.getHandler = callable -> 4;
        assertEquals(4, ARBFramebufferObject.glGenFramebuffers());
        assertEquals(4, ARBFramebufferObject.glGenRenderbuffers());
        queue.getHandler = callable -> org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
        assertEquals(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE,
                ARBFramebufferObject.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER));
        assertEquals(3, queue.uncountedGetCallCount, "gen/完整性校验归资源申请类不计数");
        assertEquals(0, queue.getCallCount, "资源申请类不得触碰计数通道");

        ARBFramebufferObject.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 4);
        ARBFramebufferObject.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 7, 0);
        ARBFramebufferObject.glRenderbufferStorage(org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, 800, 600);
        ARBFramebufferObject.glDeleteFramebuffers(4);
        assertEquals(4, queue.recorded.size());
    }

    @Test
    void gl30FramebufferCallsAreRecordedAndGensAreBlocking() {
        queue.getHandler = callable -> 6;
        assertEquals(6, GL30.glGenFramebuffers());
        assertEquals(6, GL30.glGenRenderbuffers());
        queue.getHandler = callable -> org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
        assertEquals(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE,
                GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER));
        assertEquals(3, queue.uncountedGetCallCount, "gen/完整性校验归资源申请类不计数");
        assertEquals(0, queue.getCallCount, "资源申请类不得触碰计数通道");

        GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 6);
        GL30.glFramebufferRenderbuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT, org.lwjgl.opengl.GL30.GL_RENDERBUFFER, 6);
        GL30.glGenerateMipmap(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        GL30.glDeleteRenderbuffers(6);
        assertEquals(4, queue.recorded.size());

        // IntBuffer 变体：gen 走不计数阻塞 wait、delete 走快照命令
        IntBuffer ids = ByteBuffer.allocateDirect(4).asIntBuffer();
        ids.put(6);
        ids.flip();
        GL30.glGenFramebuffers(ByteBuffer.allocateDirect(4).asIntBuffer());
        GL30.glDeleteFramebuffers(ids);
        assertEquals(1, queue.uncountedBlockingTasks.size());
        assertEquals(0, queue.blockingTasks.size());
        assertEquals(5, queue.recorded.size());
    }

    @Test
    void framebufferBindingQueryShortCircuitsToRecordSideTracking() {
        assertEquals(0, GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT));
        EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 11);
        assertEquals(11, GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT),
                "FBO 绑定查询短路到录制侧跟踪值，免阻塞往返");
        GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
        assertEquals(0, GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT));
        ARBFramebufferObject.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 7);
        assertEquals(7, GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT),
                "三族 FBO 绑定共享同一跟踪值");
        assertEquals(0, queue.getCallCount, "短路路径不得触发阻塞通道");

        // 仿真簿记覆盖的 pname 同样短路到录制侧（未绑定纹理 → 0），免阻塞往返
        assertEquals(0, GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D));
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 55);
        assertEquals(55, GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D),
                "TEXTURE_BINDING_2D 短路到 SimulatedGlState 簿记");
        assertEquals(0, queue.getCallCount, "仿真短路不得触发阻塞通道");

        // 未跟踪 pname 仍走阻塞通道
        queue.getHandler = callable -> 99;
        assertEquals(99, GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_BLEND));
        assertEquals(1, queue.getCallCount);
    }
}
