package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BufferMapEmulator} 仿真决策与在途映射生命周期验证。
 * <p>
 * 直接调用仿真入口做完整逻辑验证：access 标志判定、绑定簿记、镜像复用与增长、
 * unmap 快照内容、重入与未知 target 回退。GL 上传任务（enqueueUpload）依赖渲染
 * 线程上下文，不在单元测试覆盖范围。
 */
class BufferMapEmulatorTest {
    private static final int TARGET = GL31.GL_TEXTURE_BUFFER;
    private static final int VBO = 7;

    @AfterEach
    void tearDown() {
        BufferMapEmulator.reset();
    }

    @Test
    void writeOnlyMappingIsEmulated() {
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        ByteBuffer mirror = BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 256, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT);
        assertNotNull(mirror, "纯写映射必须仿真");
        assertEquals(0, mirror.position());
        assertEquals(256, mirror.limit());
    }

    @Test
    void readMappingFallsBack() {
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        assertNull(BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 256, GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT), "含 READ 不可仿真");
        assertNull(BufferMapEmulator.tryEmulateMapRange(TARGET, 0, 256, GL30.GL_MAP_READ_BIT));
    }

    @Test
    void unboundOrReentrantMappingFallsBack() {
        assertNull(BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 256, GL30.GL_MAP_WRITE_BIT), "未绑定 VBO 不可仿真");
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(TARGET, 0, 256, GL30.GL_MAP_WRITE_BIT));
        assertNull(BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 256, GL30.GL_MAP_WRITE_BIT), "同 target 重入映射必须回退");
    }

    @Test
    void unknownTargetFallsBack() {
        BufferMapEmulator.onBindBuffer(0xFFFF, VBO);
        assertNull(BufferMapEmulator.tryEmulateMapRange(0xFFFF, 0, 256, GL30.GL_MAP_WRITE_BIT));
    }

    @Test
    void unmapSnapshotsWrittenRange() {
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        ByteBuffer mirror = BufferMapEmulator.tryEmulateMapRange(
                TARGET, 64, 4, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT);
        assertNotNull(mirror);
        mirror.put(new byte[]{1, 2, 3, 4});

        BufferMapEmulator.PendingUpload upload = BufferMapEmulator.pollEmulatedUnmap(TARGET);
        assertNotNull(upload, "仿真映射的 unmap 必须产出上传快照");
        assertEquals(VBO, upload.vbo);
        assertEquals(64, upload.offset);
        assertEquals(4, upload.data.length);
        assertEquals(1, upload.data[0]);
        assertEquals(4, upload.data[3]);
        assertNull(BufferMapEmulator.pollEmulatedUnmap(TARGET), "重复 unmap 无在途映射");
    }

    @Test
    void mirrorIsReusedAndGrownAcrossMaps() {
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(TARGET, 0, 128, GL30.GL_MAP_WRITE_BIT));
        assertNotNull(BufferMapEmulator.pollEmulatedUnmap(TARGET));
        // 需求翻倍后镜像增长；随后的小需求复用增长后的同一镜像（视图 capacity 反映底层容量）
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(TARGET, 0, 1024, GL30.GL_MAP_WRITE_BIT));
        assertNotNull(BufferMapEmulator.pollEmulatedUnmap(TARGET));
        ByteBuffer view = BufferMapEmulator.tryEmulateMapRange(TARGET, 0, 512, GL30.GL_MAP_WRITE_BIT);
        assertNotNull(view);
        assertEquals(512, view.limit());
        assertTrue(view.capacity() >= 1024, "小需求必须复用增长后的镜像，不得新建");
        assertNotNull(BufferMapEmulator.pollEmulatedUnmap(TARGET));
    }

    @Test
    void deletedBufferDropsMirrorAndSnapshotIsNullData() {
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(TARGET, 0, 128, GL30.GL_MAP_WRITE_BIT));
        BufferMapEmulator.onDeleteBuffer(VBO);
        BufferMapEmulator.PendingUpload upload = BufferMapEmulator.pollEmulatedUnmap(TARGET);
        assertNotNull(upload);
        assertNull(upload.data, "映射期间 VBO 被删除：快照数据丢弃");
    }

    @Test
    void arrayBufferTargetIsSupported() {
        BufferMapEmulator.onBindBuffer(GL15.GL_ARRAY_BUFFER, VBO);
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(
                GL15.GL_ARRAY_BUFFER, 0, 64, GL30.GL_MAP_WRITE_BIT), "ARRAY_BUFFER 必须可仿真");
    }
}
