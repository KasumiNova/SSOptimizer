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
        // 矩阵/状态命令逐条入队；begin..end 的 5 次 immediate 调用合并为 1 条流回放命令
        assertEquals(7, queue.recorded.size());
        // 只录制不执行：无 GL 上下文也未抛异常，证明调用被完整延迟
        assertEquals(0, queue.swapCount);
        assertEquals(0, queue.swapAndSyncCount);
    }

    @Test
    void nonStreamCommandFlushesPendingVertexStreamInOrder() {
        // 顶点流与命令对象混排：非流式命令插入时，先落帧已累计的流段，
        // 帧列表顺序即录制顺序（段 1 = begin+2 顶点，其后 enable，其后段 2）
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glVertex2f(1f, 0f);
        GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        GL11.glVertex2f(1f, 1f);
        GL11.glEnd();
        assertEquals(3, queue.recorded.size(), "段1 + enable + 段2（glEnd 立即落帧）");
    }

    @Test
    void blockingGetterFlushesPendingVertexStreamBeforeSwap() {
        // drain-first 必须包含未落帧的顶点流：getter 读到此前全部录制命令执行完的状态
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        queue.getHandler = callable -> 0;
        GL11.glGetError();
        assertEquals(1, queue.recorded.size(), "顶点流段先于 swap 落帧");
        assertEquals(1, queue.swapCount);
        assertEquals(1, queue.getCallCount);
    }

    @Test
    void vertexBatchesAndDrawsUsePooledCommandObjects() {
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glEnd();
        GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_QUADS, 0, 4);
        assertEquals(2, queue.recorded.size());
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0), "顶点批次走池化回放命令");
        assertInstanceOf(DrawCommand.class, queue.recorded.get(1), "draw 走池化命令");
    }

    @Test
    void glGetStringResultIsCachedPerPname() {
        queue.getHandler = callable -> "value";
        assertEquals("value", GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
        assertEquals("value", GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
        assertEquals(1, queue.getCallCount, "同 pname 第二次命中缓存不再阻塞");
        assertEquals("value", GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
        assertEquals(2, queue.getCallCount, "不同 pname 各自取回一次");
        GL11.glGetString(0x9999);
        GL11.glGetString(0x9999);
        assertEquals(4, queue.getCallCount, "未识别 pname 不缓存逐次阻塞");
        GL11.uninstall();
        GL11.install(queue);
        GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
        assertEquals(5, queue.getCallCount, "uninstall 清空缓存后重新取回");
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

    @Test
    void extendedVertexAndStateCallsAreRecorded() {
        GL11.glVertex3f(1f, 2f, 3f);
        GL11.glVertex2d(1.0, 2.0);
        GL11.glVertex3d(1.0, 2.0, 3.0);
        GL11.glTexCoord2d(0.0, 1.0);
        GL11.glColor3f(1f, 0f, 0f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glColor3d(0.5, 0.5, 0.5);
        GL11.glColor3ub((byte) 255, (byte) 0, (byte) 0);
        GL11.glAlphaFunc(org.lwjgl.opengl.GL11.GL_GREATER, 0.1f);
        GL11.glShadeModel(org.lwjgl.opengl.GL11.GL_SMOOTH);
        GL11.glLineWidth(2f);
        GL11.glPointSize(3f);
        GL11.glPolygonMode(org.lwjgl.opengl.GL11.GL_FRONT, org.lwjgl.opengl.GL11.GL_FILL);
        GL11.glHint(org.lwjgl.opengl.GL11.GL_LINE_SMOOTH_HINT, org.lwjgl.opengl.GL11.GL_NICEST);
        GL11.glDepthMask(false);
        GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        GL11.glCullFace(org.lwjgl.opengl.GL11.GL_BACK);
        GL11.glFrontFace(org.lwjgl.opengl.GL11.GL_CCW);
        GL11.glColorMask(true, true, true, false);
        GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_REPLACE);
        GL11.glStencilMask(0xFF);
        GL11.glScissor(0, 0, 100, 100);
        GL11.glClearStencil(0);
        GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPopAttrib();
        GL11.glPushClientAttrib(org.lwjgl.opengl.GL11.GL_ALL_CLIENT_ATTRIB_BITS);
        GL11.glPopClientAttrib();
        // 前 8 次 immediate 调用合并为 1 条流回放命令（在 glAlphaFunc 前落帧），其后 20 条逐条入队
        assertEquals(21, queue.recorded.size());
    }

    @Test
    void materialAndLightCallsAreRecorded() {
        java.nio.FloatBuffer params = java.nio.ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        params.put(new float[]{1f, 1f, 1f, 1f});
        params.flip();
        java.nio.IntBuffer intParams = java.nio.ByteBuffer.allocateDirect(4 * Integer.BYTES).asIntBuffer();
        intParams.put(new int[]{1, 1, 1, 1});
        intParams.flip();

        GL11.glColorMaterial(org.lwjgl.opengl.GL11.GL_FRONT, org.lwjgl.opengl.GL11.GL_AMBIENT_AND_DIFFUSE);
        GL11.glMaterialf(org.lwjgl.opengl.GL11.GL_FRONT, org.lwjgl.opengl.GL11.GL_SHININESS, 32f);
        GL11.glMateriali(org.lwjgl.opengl.GL11.GL_FRONT, org.lwjgl.opengl.GL11.GL_SHININESS, 32);
        GL11.glMaterial(org.lwjgl.opengl.GL11.GL_FRONT, org.lwjgl.opengl.GL11.GL_AMBIENT, params);
        GL11.glMaterial(org.lwjgl.opengl.GL11.GL_FRONT, org.lwjgl.opengl.GL11.GL_AMBIENT, intParams);
        GL11.glLightf(org.lwjgl.opengl.GL11.GL_LIGHT0, org.lwjgl.opengl.GL11.GL_CONSTANT_ATTENUATION, 1f);
        GL11.glLighti(org.lwjgl.opengl.GL11.GL_LIGHT0, org.lwjgl.opengl.GL11.GL_SPOT_CUTOFF, 45);
        GL11.glLight(org.lwjgl.opengl.GL11.GL_LIGHT0, org.lwjgl.opengl.GL11.GL_POSITION, params);
        GL11.glLight(org.lwjgl.opengl.GL11.GL_LIGHT0, org.lwjgl.opengl.GL11.GL_POSITION, intParams);
        GL11.glLightModelf(org.lwjgl.opengl.GL11.GL_LIGHT_MODEL_TWO_SIDE, 0f);
        GL11.glLightModeli(org.lwjgl.opengl.GL11.GL_LIGHT_MODEL_TWO_SIDE, 0);
        GL11.glLightModel(org.lwjgl.opengl.GL11.GL_LIGHT_MODEL_AMBIENT, params);
        GL11.glLightModel(org.lwjgl.opengl.GL11.GL_LIGHT_MODEL_AMBIENT, intParams);
        assertEquals(13, queue.recorded.size());
    }

    @Test
    void textureAndPixelCallsAreRecorded() {
        java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(4);
        java.nio.IntBuffer ids = java.nio.ByteBuffer.allocateDirect(2 * Integer.BYTES).asIntBuffer();
        ids.put(new int[]{1, 2});
        ids.flip();
        java.nio.FloatBuffer priorities = java.nio.ByteBuffer.allocateDirect(2 * Float.BYTES).asFloatBuffer();
        priorities.put(new float[]{1f, 0.5f});
        priorities.flip();

        GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                2, 2, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                2, 2, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexImage1D(org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                2, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0,
                2, 2, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glTexSubImage1D(org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 0, 0,
                2, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glCopyTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA, 0, 0, 2, 2, 0);
        GL11.glCopyTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, 2, 2);
        GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        GL11.glTexParameterf(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_PRIORITY, 1f);
        GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glDeleteTextures(42);
        GL11.glDeleteTextures(ids);
        GL11.glPrioritizeTextures(ids, priorities);
        assertEquals(13, queue.recorded.size());
    }

    @Test
    void displayListCallsAreRecordedExceptBlockingGen() {
        queue.getHandler = callable -> 7;
        assertEquals(7, GL11.glGenLists(1), "glGenLists 走不计数阻塞通道取回");
        assertEquals(1, queue.uncountedGetCallCount);
        assertEquals(0, queue.getCallCount, "资源申请类不得触碰计数通道");
        GL11.glNewList(7, org.lwjgl.opengl.GL11.GL_COMPILE);
        GL11.glEndList();
        GL11.glCallList(7);
        GL11.glDeleteLists(7, 1);
        assertEquals(4, queue.recorded.size());
    }

    @Test
    void gettersRouteThroughBlockingChannel() {
        queue.getHandler = callable -> 0;
        GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
        GL11.glGetError();
        queue.getHandler = callable -> 0.5f;
        GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_LINE_WIDTH);
        queue.getHandler = callable -> true;
        GL11.glGetBoolean(org.lwjgl.opengl.GL11.GL_BLEND);
        GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
        GL11.glIsTexture(1);
        GL11.glIsList(1);
        queue.getHandler = callable -> "stub";
        assertEquals("stub", GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
        assertEquals(8, queue.getCallCount, "标量 getter 全部走阻塞取值通道");
        assertEquals(0, queue.recorded.size());

        java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(4);
        GL11.glReadPixels(0, 0, 1, 1,
                org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        java.nio.IntBuffer out = java.nio.ByteBuffer.allocateDirect(4).asIntBuffer();
        GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_SCISSOR_BOX, out);
        assertEquals(2, queue.blockingTasks.size(), "写入调用方 buffer 的回读走阻塞 wait 通道");
    }

    @Test
    void clientStateCallsAreRecorded() {
        GL11.glEnableClientState(org.lwjgl.opengl.GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(org.lwjgl.opengl.GL11.GL_COLOR_ARRAY);
        assertEquals(2, queue.recorded.size());
    }

    // -- 状态命令去重（StateDedup）--

    @Test
    void consecutiveIdenticalStateCommandsAreDeduplicated() {
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        assertEquals(2, queue.recorded.size(), "连续相同状态命令只入队一次");
    }

    @Test
    void stateCommandDedupBreaksOnInterleavedCommand() {
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND); // 不同命令插入 → 打断相邻性
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        assertEquals(3, queue.recorded.size(), "插入命令后相同状态命令必须重新入队");
    }

    @Test
    void stateCommandDedupBreaksOnAuxProducerCommit() {
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        // aux 生产者线程并发提交：直接向帧追加命令（绕过主线程录制上下文）
        queue.frame.add(() -> {
        });
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        assertEquals(2, queue.recorded.size(), "aux 提交必须打断相邻性，第二条绑定重新入队");
    }

    @Test
    void stateCommandDedupBreaksOnVertexStreamFlush() {
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glEnd(); // glEnd 立即落帧，流命令插入打断 bind 相邻性
        GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
        assertEquals(3, queue.recorded.size(), "顶点流落帧命令必须打断相邻性");
    }

    @Test
    void dedupDisabledByFlagRecordsEveryStateCommand() {
        boolean saved = BridgeSupport.stateDedupEnabled;
        try {
            BridgeSupport.stateDedupEnabled = false;
            GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
            GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 42);
            GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
            GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
            assertEquals(4, queue.recorded.size(), "开关关闭时每条状态命令照常入队");
        } finally {
            BridgeSupport.stateDedupEnabled = saved;
        }
    }

    // -- 流内状态指令（sprite 渲染路径，见 SpriteRenderHelper 的流内化）--

    @Test
    void streamStateInstructionsEncodeIntoOneVertexBatch() {
        // sprite 的完整绘制（bind + enable + blendFunc + quad 段 + disable）全部
        // 编码进顶点流：glEnd 处一次落帧成 1 条流命令（状态命令数从 5 条降为 0）
        GL11.streamBindTexture(7);
        GL11.streamEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        GL11.streamEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        GL11.streamBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE);
        GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        GL11.glColor4ub((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        GL11.glTexCoord2f(0f, 0f);
        GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(1f, 0f);
        GL11.glVertex2f(1f, 0f);
        GL11.glTexCoord2f(1f, 1f);
        GL11.glVertex2f(1f, 1f);
        GL11.glTexCoord2f(0f, 1f);
        GL11.glVertex2f(0f, 1f);
        GL11.glEnd();
        GL11.streamDisable(org.lwjgl.opengl.GL11.GL_BLEND);

        assertEquals(1, queue.recorded.size(), "sprite 的 bind+状态+quad+disable 编码进一条流命令");
        assertInstanceOf(VertexBatchCommand.class, queue.recorded.get(0),
                "整段 sprite 绘制（含状态）为一条流回放命令");
    }

    @Test
    void consecutiveStreamSpritesEachProduceOneBatch() {
        // 连续两个 sprite：各自 glEnd 落帧为 1 条流命令（glEnd 保持「段即批次」
        // 语义，避免挂起流与 aux 提交的帧列表错序）；状态命令已合并进流，
        // 每 sprite 的命令数从 6 条（5 状态 + 1 流）降为 1 条流命令
        for (int sprite = 0; sprite < 2; sprite++) {
            GL11.streamBindTexture(7);
            GL11.streamEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
            GL11.streamEnable(org.lwjgl.opengl.GL11.GL_BLEND);
            GL11.streamBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE);
            GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
            GL11.glTexCoord2f(0f, 0f);
            GL11.glVertex2f(sprite, 0f);
            GL11.glTexCoord2f(1f, 0f);
            GL11.glVertex2f(sprite, 1f);
            GL11.glEnd();
            GL11.streamDisable(org.lwjgl.opengl.GL11.GL_BLEND);
        }
        assertEquals(2, queue.recorded.size(), "两个 sprite 各一条流命令（状态合并进流）");
    }
}
