package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 桥层覆盖面补齐（BoxUtil 1.0.6 缺口清单）的录制行为验证。
 * <p>
 * 新增桥方法全部走录制/阻塞通道，不在调用线程触碰真实 GL：录制命令入队、
 * 快照在录制时刻深拷贝、查询走阻塞取值、fence 等待在调用线程自旋。
 */
class BridgeGapFillTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        BridgeSupport.install(queue);
    }

    @AfterEach
    void tearDown() {
        BridgeSupport.uninstall();
        BufferMapEmulator.reset();
    }

    private static ByteBuffer bytes(int n) {
        return ByteBuffer.allocateDirect(n);
    }

    private static IntBuffer ints(int n) {
        return ByteBuffer.allocateDirect(n * Integer.BYTES).asIntBuffer();
    }

    private static ShortBuffer shorts(int n) {
        return ByteBuffer.allocateDirect(n * Short.BYTES).asShortBuffer();
    }

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(n * Float.BYTES).asFloatBuffer();
    }

    private static java.nio.DoubleBuffer doubles(int n) {
        return ByteBuffer.allocateDirect(n * Double.BYTES).asDoubleBuffer();
    }

    // ------------------------------------------------------------------
    // GL12 glDrawRangeElements 族（崩溃现场 API）
    // ------------------------------------------------------------------

    @Test
    void gl12DrawRangeElementsAllOverloadsRecorded() {
        int triangles = org.lwjgl.opengl.GL11.GL_TRIANGLES;
        GL12.glDrawRangeElements(triangles, 0, 10, bytes(12));
        GL12.glDrawRangeElements(triangles, 0, 10, ints(3));
        GL12.glDrawRangeElements(triangles, 0, 10, shorts(3));
        GL12.glDrawRangeElements(triangles, 0, 10, 3, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT, 0L);
        assertEquals(4, queue.recorded.size(), "四个重载都必须各录一条命令");
        assertEquals(0, queue.blockingTasks.size());
        assertEquals(0, queue.getCallCount);
    }

    // ------------------------------------------------------------------
    // GL31/GL32/GL40/GL42/GL43 绘制族
    // ------------------------------------------------------------------

    @Test
    void gl31DrawElementsInstancedRecorded() {
        int triangles = org.lwjgl.opengl.GL11.GL_TRIANGLES;
        GL31.glDrawElementsInstanced(triangles, bytes(12), 4);
        GL31.glDrawElementsInstanced(triangles, ints(3), 4);
        GL31.glDrawElementsInstanced(triangles, shorts(3), 4);
        GL31.glDrawElementsInstanced(triangles, 3, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT, 0L, 4);
        assertEquals(4, queue.recorded.size());
    }

    @Test
    void gl32BaseVertexDrawFamilyRecorded() {
        int triangles = org.lwjgl.opengl.GL11.GL_TRIANGLES;
        int unsignedInt = org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
        GL32.glDrawElementsBaseVertex(triangles, bytes(12), 1);
        GL32.glDrawElementsBaseVertex(triangles, ints(3), 1);
        GL32.glDrawElementsBaseVertex(triangles, shorts(3), 1);
        GL32.glDrawElementsBaseVertex(triangles, 3, unsignedInt, 0L, 1);
        GL32.glDrawRangeElementsBaseVertex(triangles, 0, 10, bytes(12), 1);
        GL32.glDrawRangeElementsBaseVertex(triangles, 0, 10, ints(3), 1);
        GL32.glDrawRangeElementsBaseVertex(triangles, 0, 10, shorts(3), 1);
        GL32.glDrawRangeElementsBaseVertex(triangles, 0, 10, 3, unsignedInt, 0L, 1);
        GL32.glDrawElementsInstancedBaseVertex(triangles, bytes(12), 4, 1);
        GL32.glDrawElementsInstancedBaseVertex(triangles, ints(3), 4, 1);
        GL32.glDrawElementsInstancedBaseVertex(triangles, shorts(3), 4, 1);
        GL32.glDrawElementsInstancedBaseVertex(triangles, 3, unsignedInt, 0L, 4, 1);
        assertEquals(12, queue.recorded.size());
    }

    @Test
    void gl32StateFamilyRecorded() {
        GL32.glFramebufferTexture(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, 1, 0);
        GL32.glTexImage2DMultisample(org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE,
                4, org.lwjgl.opengl.GL11.GL_RGBA8, 256, 256, true);
        GL32.glTexImage3DMultisample(org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY,
                4, org.lwjgl.opengl.GL11.GL_RGBA8, 256, 256, 2, true);
        GL32.glSampleMaski(0, 0xFFFF);
        assertEquals(4, queue.recorded.size());
    }

    @Test
    void gl40IndirectDrawFamilyRecorded() {
        int triangles = org.lwjgl.opengl.GL11.GL_TRIANGLES;
        GL40.glDrawArraysIndirect(triangles, bytes(16));
        GL40.glDrawArraysIndirect(triangles, ints(4));
        GL40.glDrawArraysIndirect(triangles, 0L);
        GL40.glDrawElementsIndirect(triangles, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT, bytes(20));
        GL40.glDrawElementsIndirect(triangles, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT, ints(5));
        GL40.glDrawElementsIndirect(triangles, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT, 0L);
        GL40.glPatchParameter(org.lwjgl.opengl.GL40.GL_PATCH_DEFAULT_OUTER_LEVEL, floats(4));
        assertEquals(7, queue.recorded.size());
    }

    @Test
    void gl42BaseInstanceDrawFamilyRecorded() {
        int triangles = org.lwjgl.opengl.GL11.GL_TRIANGLES;
        int unsignedInt = org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
        GL42.glDrawArraysInstancedBaseInstance(triangles, 0, 3, 4, 1);
        GL42.glDrawElementsInstancedBaseInstance(triangles, bytes(12), 4, 1);
        GL42.glDrawElementsInstancedBaseInstance(triangles, ints(3), 4, 1);
        GL42.glDrawElementsInstancedBaseInstance(triangles, shorts(3), 4, 1);
        GL42.glDrawElementsInstancedBaseInstance(triangles, 3, unsignedInt, 0L, 4, 1);
        GL42.glDrawElementsInstancedBaseVertexBaseInstance(triangles, bytes(12), 4, 2, 1);
        GL42.glDrawElementsInstancedBaseVertexBaseInstance(triangles, ints(3), 4, 2, 1);
        GL42.glDrawElementsInstancedBaseVertexBaseInstance(triangles, shorts(3), 4, 2, 1);
        GL42.glDrawElementsInstancedBaseVertexBaseInstance(triangles, 3, unsignedInt, 0L, 4, 2, 1);
        assertEquals(9, queue.recorded.size());
    }

    @Test
    void gl43MultiIndirectAndStateFamilyRecorded() {
        int triangles = org.lwjgl.opengl.GL11.GL_TRIANGLES;
        int unsignedInt = org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
        GL43.glMultiDrawArraysIndirect(triangles, bytes(16), 1, 0);
        GL43.glMultiDrawArraysIndirect(triangles, ints(4), 1, 0);
        GL43.glMultiDrawArraysIndirect(triangles, 0L, 1, 0);
        GL43.glMultiDrawElementsIndirect(triangles, unsignedInt, bytes(20), 1, 0);
        GL43.glMultiDrawElementsIndirect(triangles, unsignedInt, ints(5), 1, 0);
        GL43.glMultiDrawElementsIndirect(triangles, unsignedInt, 0L, 1, 0);
        GL43.glShaderStorageBlockBinding(1, 0, 2);
        GL43.glTexBufferRange(org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER,
                org.lwjgl.opengl.GL30.GL_RGBA32F, 9, 0L, 1024L);
        GL43.glFramebufferParameteri(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL43.GL_FRAMEBUFFER_DEFAULT_WIDTH, 256);
        GL43.glInvalidateBufferSubData(9, 0L, 512L);
        GL43.glInvalidateFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, ints(1));
        GL43.glInvalidateSubFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, ints(1), 0, 0, 8, 8);
        GL43.glInvalidateTexImage(3, 0);
        GL43.glInvalidateTexSubImage(3, 0, 0, 0, 0, 8, 8, 1);
        GL43.glTexStorage2DMultisample(org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE,
                4, org.lwjgl.opengl.GL11.GL_RGBA8, 64, 64, true);
        GL43.glTexStorage3DMultisample(org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY,
                4, org.lwjgl.opengl.GL11.GL_RGBA8, 64, 64, 2, true);
        GL43.glVertexBindingDivisor(0, 1);
        assertEquals(17, queue.recorded.size());
    }

    // ------------------------------------------------------------------
    // GL13/GL14/GL15/GL30/GL44 + ARB/EXT FBO 状态缓冲族
    // ------------------------------------------------------------------

    @Test
    void gl13CompressedTextureFamilyRecorded() {
        int texture2d = org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
        GL13.glClientActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        GL13.glCompressedTexImage1D(org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 0, 0x83F1, 64, 0, bytes(32));
        GL13.glCompressedTexImage1D(org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 0, 0x83F1, 64, 0, 32, 0L);
        GL13.glCompressedTexImage2D(texture2d, 0, 0x83F1, 64, 64, 0, 2048, 0L);
        GL13.glCompressedTexImage3D(org.lwjgl.opengl.GL12.GL_TEXTURE_3D, 0, 0x83F1, 16, 16, 16, 0, bytes(2048));
        GL13.glCompressedTexImage3D(org.lwjgl.opengl.GL12.GL_TEXTURE_3D, 0, 0x83F1, 16, 16, 16, 0, 2048, 0L);
        GL13.glCompressedTexSubImage1D(org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 0, 0, 64, 0x83F1, bytes(32));
        GL13.glCompressedTexSubImage1D(org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 0, 0, 64, 0x83F1, 32, 0L);
        GL13.glCompressedTexSubImage2D(texture2d, 0, 0, 0, 64, 64, 0x83F1, bytes(2048));
        GL13.glCompressedTexSubImage2D(texture2d, 0, 0, 0, 64, 64, 0x83F1, 2048, 0L);
        GL13.glCompressedTexSubImage3D(org.lwjgl.opengl.GL12.GL_TEXTURE_3D, 0, 0, 0, 0,
                16, 16, 16, 0x83F1, bytes(2048));
        GL13.glCompressedTexSubImage3D(org.lwjgl.opengl.GL12.GL_TEXTURE_3D, 0, 0, 0, 0,
                16, 16, 16, 0x83F1, 2048, 0L);
        assertEquals(12, queue.recorded.size());
    }

    @Test
    void gl13GetCompressedTexImageBlocks() {
        GL13.glGetCompressedTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, bytes(64));
        GL13.glGetCompressedTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0L);
        assertEquals(2, queue.blockingTasks.size(), "读回必须走阻塞通道（语义强依赖执行完成）");
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void gl14MultiDrawArraysRecorded() {
        GL14.glMultiDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, ints(2), ints(2));
        assertEquals(1, queue.recorded.size());
    }

    @Test
    void gl15GetBufferSubDataBlocks() {
        GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0L, bytes(16));
        GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0L, doubles(2));
        GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0L, floats(4));
        GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0L, ints(4));
        GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0L, shorts(8));
        assertEquals(5, queue.blockingTasks.size(), "读回必须走阻塞通道");
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void gl30GapFillRecorded() {
        GL30.glBindBufferRange(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, 0, 5, 0L, 256L);
        GL30.glFramebufferTexture1D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 1, 0);
        GL30.glFramebufferTexture3D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL12.GL_TEXTURE_3D, 1, 0, 0);
        GL30.glFramebufferTextureLayer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, 1, 0, 0);
        GL30.glRenderbufferStorageMultisample(org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                4, org.lwjgl.opengl.GL11.GL_RGBA8, 64, 64);
        GL30.glClearBuffer(org.lwjgl.opengl.GL11.GL_COLOR, 0, floats(4));
        GL30.glClearBuffer(org.lwjgl.opengl.GL11.GL_STENCIL, 0, ints(1));
        GL30.glClearBufferu(org.lwjgl.opengl.GL11.GL_COLOR, 0, ints(4));
        GL30.glClearBufferfi(org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL, 0, 1.0f, 0);
        GL30.glDeleteVertexArrays(ints(2));
        GL30.glBindFragDataLocation(1, 0, "outColor");
        assertEquals(11, queue.recorded.size());
        GL30.glGenVertexArrays(ints(2));
        assertEquals(1, queue.uncountedBlockingTasks.size(), "VAO 批量分配走资源阻塞通道");
    }

    @Test
    void gl30FlushMappedBufferRangeNoOpsOnlyUnderEmulatedMap() {
        int target = org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
        // 无在途映射：入队真实 flush
        GL30.glFlushMappedBufferRange(target, 0L, 64L);
        assertEquals(1, queue.recorded.size());
        // 有在途仿真映射：no-op（unmap 时整区间快照上传，flush 区间是其子集）
        BufferMapEmulator.onBindBuffer(target, 7);
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(target, 0, 64,
                org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL30.GL_MAP_FLUSH_EXPLICIT_BIT));
        GL30.glFlushMappedBufferRange(target, 0L, 32L);
        assertEquals(1, queue.recorded.size(), "仿真映射在途时 flush 必须是 no-op");
        assertNotNull(BufferMapEmulator.pollEmulatedUnmap(target));
        GL30.glFlushMappedBufferRange(target, 0L, 64L);
        assertEquals(2, queue.recorded.size(), "unmap 后 flush 恢复真实入队");
    }

    @Test
    void gl31UniformBlockQueryFamily() {
        queue.getHandler = callable -> 7;
        assertEquals(7, GL31.glGetActiveUniformBlocki(1, 0,
                org.lwjgl.opengl.GL31.GL_UNIFORM_BLOCK_DATA_SIZE));
        assertEquals(7, GL31.glGetActiveUniformsi(1, 0, 0x8A37 /* GL_UNIFORM_TYPE */));
        assertEquals(2, queue.getCallCount, "单值查询走计数阻塞取值通道");
        assertEquals(7, GL31.glGetUniformBlockIndex(1, "Block"));
        assertEquals(1, queue.uncountedGetCallCount, "名称查询走资源通道（不计数）");
        // ByteBuffer 名称形态：NUL 结尾字节在录制时刻读出，position 不被消费
        ByteBuffer name = bytes(16);
        name.put(new byte[]{'B', 'l', 'o', 'c', 'k', 0, 'x', 'y'}).flip();
        int pos = name.position();
        assertEquals(7, GL31.glGetUniformBlockIndex(1, name));
        assertEquals(pos, name.position(), "名称读出不得消耗调用方 position");
    }

    @Test
    void gl32ClientWaitSyncSatisfiedAndTimeout() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        // fake queue 只录不执行：SignalFenceCommand 不会运行，fence 保持未 signal
        long start = System.nanoTime();
        assertEquals(org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED,
                GL32.glClientWaitSync(sync, 0, 2_000_000L), "未 signal 必须等到超时");
        assertTrue(System.nanoTime() - start >= 2_000_000L, "等待时长不得短于 timeout");

        sync.fence().signal(); // 模拟渲染线程/CPU 生产者会合
        assertEquals(org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED,
                GL32.glClientWaitSync(sync, 0, 1_000_000L));
    }

    @Test
    void gl32GetSynciReportsFenceState() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertEquals(org.lwjgl.opengl.GL32.GL_UNSIGNALED,
                GL32.glGetSynci(sync, org.lwjgl.opengl.GL32.GL_SYNC_STATUS));
        sync.fence().signal();
        assertEquals(org.lwjgl.opengl.GL32.GL_SIGNALED,
                GL32.glGetSynci(sync, org.lwjgl.opengl.GL32.GL_SYNC_STATUS));
        assertEquals(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,
                GL32.glGetSynci(sync, org.lwjgl.opengl.GL32.GL_SYNC_CONDITION));
        assertEquals(0, GL32.glGetSynci(sync, org.lwjgl.opengl.GL32.GL_SYNC_FLAGS));
        assertThrows(IllegalArgumentException.class, () -> GL32.glGetSynci(sync, 0x9999));
    }

    @Test
    void gl32GetSyncBufferAndScalarForms() {
        // BoxUtil Operation$Sync.init:3308 崩溃现场 API：buffer 形态必须与
        // glGetSynci 同一语义源（折叠模型纯 CPU 查询）
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        IntBuffer values = ints(2);
        IntBuffer length = ints(1);
        GL32.glGetSync(sync, org.lwjgl.opengl.GL32.GL_OBJECT_TYPE, length, values);
        assertEquals(org.lwjgl.opengl.GL32.GL_SYNC_FENCE, values.get(0), "OBJECT_TYPE 恒为 fence");
        assertEquals(1, length.get(0), "length 必须收到实际写入个数");
        assertEquals(1, values.position(), "值写入当前位置并推进 position");
        GL32.glGetSync(sync, org.lwjgl.opengl.GL32.GL_SYNC_STATUS, null, values);
        assertEquals(org.lwjgl.opengl.GL32.GL_UNSIGNALED, values.get(1), "length 允许为 null");
        assertEquals(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,
                GL32.glGetSync(sync, org.lwjgl.opengl.GL32.GL_SYNC_CONDITION), "单值形态等价 glGetSynci");
        assertThrows(IllegalArgumentException.class,
                () -> GL32.glGetSync(sync, org.lwjgl.opengl.GL32.GL_SYNC_STATUS, null, ints(0)),
                "values 容量不足必须显式报错");
        assertThrows(IllegalArgumentException.class,
                () -> GL32.glGetSync(sync, 0x9999, null, ints(1)), "未知 pname 必须显式报错");
    }

    @Test
    void gl32IsSyncReflectsDeleteMark() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertTrue(GL32.glIsSync(sync));
        GL32.glDeleteSync(sync);
        assertFalse(GL32.glIsSync(sync));
    }

    @Test
    void arbSyncFamilyDelegatesToGl32() {
        // ARB 回退分支（BoxUtil Operation$Sync 的 GL_ARB_sync 路径）走同一折叠语义
        GLSync sync = ARBSync.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertTrue(ARBSync.glIsSync(sync));
        assertEquals(org.lwjgl.opengl.GL32.GL_UNSIGNALED,
                ARBSync.glGetSynci(sync, org.lwjgl.opengl.GL32.GL_SYNC_STATUS));
        IntBuffer values = ints(1);
        ARBSync.glGetSync(sync, org.lwjgl.opengl.GL32.GL_SYNC_FLAGS, null, values);
        assertEquals(0, values.get(0));
        assertEquals(0, ARBSync.glGetSync(sync, org.lwjgl.opengl.GL32.GL_SYNC_FLAGS));
        assertEquals(org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED,
                ARBSync.glClientWaitSync(sync, 0, 1_000_000L), "fake queue 不执行，fence 未 signal");
        int recordedBefore = queue.recorded.size();
        ARBSync.glWaitSync(sync, 0, -1L);
        assertEquals(recordedBefore + 1, queue.recorded.size(), "glWaitSync 必须录制等待命令");
        ARBSync.glDeleteSync(sync);
        assertFalse(ARBSync.glIsSync(sync));
    }

    @Test
    void gl44AndGl41GapFillRecorded() {
        GL44.glBufferStorage(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, doubles(4), 0);
        GL44.glBufferStorage(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, ints(4), 0);
        GL44.glBufferStorage(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, shorts(8), 0);
        GL44.glBindTextures(0, 2, ints(2));
        GL41.glVertexAttribLPointer(0, 4, 0, 0L);
        assertEquals(5, queue.recorded.size());
    }

    @Test
    void arbExtFramebufferGapFillRecorded() {
        ARBFramebufferObject.glBlitFramebuffer(0, 0, 64, 64, 0, 0, 64, 64,
                org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT, org.lwjgl.opengl.GL11.GL_NEAREST);
        ARBFramebufferObject.glFramebufferTexture1D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 1, 0);
        ARBFramebufferObject.glFramebufferTexture3D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL12.GL_TEXTURE_3D, 1, 0, 0);
        ARBFramebufferObject.glFramebufferTextureLayer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, 1, 0, 0);
        ARBFramebufferObject.glRenderbufferStorageMultisample(org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                4, org.lwjgl.opengl.GL11.GL_RGBA8, 64, 64);
        EXTFramebufferObject.glFramebufferTexture1DEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                org.lwjgl.opengl.EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                org.lwjgl.opengl.GL11.GL_TEXTURE_1D, 1, 0);
        EXTFramebufferObject.glFramebufferTexture3DEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                org.lwjgl.opengl.EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                org.lwjgl.opengl.GL12.GL_TEXTURE_3D, 1, 0, 0);
        assertEquals(7, queue.recorded.size());
    }
}
