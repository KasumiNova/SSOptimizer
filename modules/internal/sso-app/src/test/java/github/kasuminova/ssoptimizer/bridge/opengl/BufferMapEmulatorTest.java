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

    @Test
    void persistentOrCoherentMappingFallsBack() {
        BufferMapEmulator.onBindBuffer(TARGET, VBO);
        assertNull(BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 256, GL30.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT),
                "WRITE|PERSISTENT 必须回退真实映射");
        assertNull(BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 256, GL30.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT
                        | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT),
                "WRITE|PERSISTENT|COHERENT 必须回退真实映射");
        assertNotNull(BufferMapEmulator.tryEmulateMapRange(
                TARGET, 0, 64, GL30.GL_MAP_WRITE_BIT), "持久映射判退不得影响后续仿真");
        assertNotNull(BufferMapEmulator.pollEmulatedUnmap(TARGET));
    }

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
