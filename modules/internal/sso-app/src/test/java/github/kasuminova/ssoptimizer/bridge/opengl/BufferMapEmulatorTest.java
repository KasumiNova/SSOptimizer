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

    /**
     * 映射视图必须保留镜像字节序：{@code duplicate()} 会把序重置为 BIG_ENDIAN（JDK 行为），
     * 若不复原，调用方经 {@code asFloatBuffer()} 写入的节点数据会以翻转字节序落进快照，
     * 上传 GPU 后按原生序解读即成垃圾坐标（TrailEntity 弧拉成超长线段的根因）。
     */
    @Test
    void mappedViewPreservesMirrorByteOrder() {
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        ByteBuffer mirror = BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 8, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT);
        assertNotNull(mirror);
        assertEquals(java.nio.ByteOrder.nativeOrder(), mirror.order(),
                "映射视图字节序必须与 LWJGL 镜像一致（原生序）");

        mirror.asFloatBuffer().put(new float[]{1.0306f, 4.421f});
        BufferMapEmulator.PendingUpload upload = BufferMapEmulator.pollEmulatedUnmap(TARGET);
        assertNotNull(upload);
        // 快照字节必须与原生序 putFloat 的落字节一致（翻转序会产生 BE 编码字节）
        ByteBuffer expected = ByteBuffer.allocate(8).order(java.nio.ByteOrder.nativeOrder());
        expected.putFloat(1.0306f).putFloat(4.421f);
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.array(), upload.data);
    }

    @Test
    void arrayBufferTargetIsSupported() {
        BufferMapEmulator.onBindBuffer(GL15.GL_ARRAY_BUFFER, VBO);
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(
                GL15.GL_ARRAY_BUFFER, 0, 64, GL30.GL_MAP_WRITE_BIT), "ARRAY_BUFFER 必须可仿真");
    }

    /**
     * BoxUtil 折叠进主帧后的多线程在途映射：两条线程在同一 target（不同 VBO）上并发持有
     * 写映射时，双方都必须独立仿真且 unmap 各配各对——后到 unmap 的一方不得消费另一方
     * 的在途登记（否则另一方尚在写入的镜像会被提前快照上传，实例数据/节点数据错乱）。
     */
    @Test
    void concurrentCrossThreadMappingsAreIsolated() throws InterruptedException {
        final int vboB = VBO + 1;
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        ByteBuffer mirrorA = BufferMapEmulator.tryEmulateMapRange(
                TARGET, 64, 4, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT);
        assertNotNull(mirrorA);

        final ByteBuffer[] mirrorB = new ByteBuffer[1];
        final BufferMapEmulator.PendingUpload[] uploadB = new BufferMapEmulator.PendingUpload[1];
        Thread threadB = new Thread(() -> {
            BufferMapEmulator.onBindBuffer(TARGET, vboB);
            mirrorB[0] = BufferMapEmulator.tryEmulateMapRange(
                    TARGET, 128, 8, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT);
            if (mirrorB[0] == null) {
                return;
            }
            mirrorB[0].put(new byte[]{11, 12, 13, 14, 15, 16, 17, 18});
            uploadB[0] = BufferMapEmulator.pollEmulatedUnmap(TARGET);
        });
        threadB.start();
        threadB.join();

        assertNotNull(mirrorB[0], "另一线程的同 target 在途映射不得逼退本线程仿真");
        assertNotNull(uploadB[0], "另一线程的 unmap 必须配对自己的在途映射");
        assertEquals(vboB, uploadB[0].vbo);
        assertEquals(128, uploadB[0].offset);
        assertEquals(8, uploadB[0].data.length);
        assertEquals(11, uploadB[0].data[0]);
        assertEquals(18, uploadB[0].data[7]);

        // B 已 unmap 后 A 才写完：A 的快照必须取自 A 写完之后的镜像，且 VBO/偏移不被 B 串扰
        mirrorA.put(new byte[]{1, 2, 3, 4});
        BufferMapEmulator.PendingUpload uploadA = BufferMapEmulator.pollEmulatedUnmap(TARGET);
        assertNotNull(uploadA, "A 的 unmap 必须仍能找到自己的在途映射（未被 B 消费）");
        assertEquals(VBO, uploadA.vbo);
        assertEquals(64, uploadA.offset);
        assertEquals(4, uploadA.data.length);
        assertEquals(1, uploadA.data[0]);
        assertEquals(4, uploadA.data[3]);
        assertNull(BufferMapEmulator.pollEmulatedUnmap(TARGET), "双方 unmap 后无残留登记");
    }

    /**
     * 绑定簿记按线程隔离：线程 B 的 bind 不得覆盖线程 A 的绑定归属——非 RT 下各线程
     * 各有独立 GL 上下文（BoxUtil SharedDrawable 模型），bind/map 交错时 A 的映射必须
     * 仍归属 A 自己绑定的 VBO，否则 A 的数据会被上传到 B 的池。
     */
    @Test
    void bindingBookkeepingIsPerThread() throws InterruptedException {
        final int vboB = VBO + 1;
        BufferMapEmulator.onBindBuffer(TARGET, VBO);

        Thread threadB = new Thread(() -> BufferMapEmulator.onBindBuffer(TARGET, vboB));
        threadB.start();
        threadB.join();

        ByteBuffer mirrorA = BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 4, GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_RANGE_BIT);
        assertNotNull(mirrorA, "B 的 bind 不得逼退 A 的仿真");
        mirrorA.put(new byte[]{5, 6, 7, 8});
        BufferMapEmulator.PendingUpload uploadA = BufferMapEmulator.pollEmulatedUnmap(TARGET);
        assertNotNull(uploadA);
        assertEquals(VBO, uploadA.vbo, "A 的映射必须归属 A 自己绑定的 VBO，而非 B 后绑的");
    }
}
