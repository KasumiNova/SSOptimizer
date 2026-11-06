package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.queue.SuspendFrameException;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MappedWriteBridgeImplTest {
    private static final int VBO_ID = 42;

    private static final class FakeSyncOps implements RealSyncOps {
        final List<Object> created = new CopyOnWriteArrayList<>();
        final List<Object> clientWaited = new CopyOnWriteArrayList<>();
        final List<Object> deleted = new CopyOnWriteArrayList<>();
        final List<Integer> barriers = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile int clientWaitStatus = org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
        private int nextToken;

        @Override
        public Object fenceSync(final int condition, final int flags) {
            Object token = "sync-" + (++nextToken);
            created.add(token);
            events.add("fenceSync:" + token);
            return token;
        }

        @Override
        public void waitSync(final Object sync, final int flags, final long timeout) {
        }

        @Override
        public int clientWaitSync(final Object sync, final int flags, final long timeout) {
            clientWaited.add(sync);
            events.add("clientWait:" + sync);
            return clientWaitStatus;
        }

        @Override
        public void deleteSync(final Object sync) {
            deleted.add(sync);
            events.add("deleteSync:" + sync);
        }

        @Override
        public void memoryBarrier(final int barriersMask) {
            barriers.add(barriersMask);
            events.add("memoryBarrier");
        }
    }

    private RenderQueueImpl queue;
    private FakeSyncOps syncOps;

    @BeforeEach
    void setUp() {
        RealMappingRegistry.reset();
        MappedWriteBridgeImpl.resetForTesting();
        BufferMapEmulator.reset();
        syncOps = new FakeSyncOps();
    }

    @AfterEach
    void tearDown() {
        RenderQueueImpl.frameEndMarkerHook(null);
        if (queue != null) {
            queue.shutdown();
            queue = null;
        }
        BridgeSupport.uninstall();
        RealMappingRegistry.reset();
        MappedWriteBridgeImpl.resetForTesting();
        BufferMapEmulator.reset();
        RenderThreadMode.resetLoadingFinishedForTesting();
    }

    private void installRealQueue() {
        queue = new RenderQueueImpl();
        BridgeSupport.install(queue);
        RenderQueueImpl.frameEndMarkerHook(MappedWriteBridgeImpl::frameEndMarker);
        BridgeSupport.syncOpsForTesting(syncOps);
        BridgeSupport.resetPoolForTesting();
    }

    private void syncRenderThread() {
        queue.get(() -> null);
    }

    private static void awaitLatch(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    private static IntBuffer sourceOf(final int... values) {
        IntBuffer buf = ByteBuffer.allocateDirect(values.length * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(values).flip();
        return buf;
    }

    private static void assertTargetInts(final ByteBuffer target, final int... expected) {
        IntBuffer view = target.asIntBuffer();
        assertEquals(expected.length, view.remaining(), "target 容量与预期不符");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], view.get(i), "target int[" + i + "]");
        }
    }

    @Test
    void orderedWriteWaitsFrameEndFenceAndCopiesBytes() {
        installRealQueue();
        ByteBuffer target = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        RealMappingRegistry.Token token = RealMappingRegistry.track(VBO_ID, target);
        assertNotNull(token);
        BufferSnapshotPool pool = BridgeSupport.pool();

        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1, 2, 3, 4));
        queue.swapFrames();
        syncRenderThread();

        assertTargetInts(target, 1, 2, 3, 4);
        assertEquals(1, syncOps.created.size(), "帧 0 帧尾 marker 必须发布首个 fence");
        assertTrue(syncOps.clientWaited.isEmpty(), "首帧写入时 fence 尚未发布，不做等待");
        assertEquals(List.of(org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT),
                syncOps.barriers, "落笔后必须插入客户端映射屏障");
        assertEquals(1, pool.pooledBufferCount(), "写入命令执行完必须归还快照");
        assertEquals(1, pool.totalAllocations());

        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(5, 6, 7, 8));
        queue.swapFrames();
        syncRenderThread();

        assertTargetInts(target, 5, 6, 7, 8);
        assertEquals(List.of(syncOps.created.get(0)), syncOps.clientWaited,
                "第二帧写入必须等待上一帧帧尾 fence");
        assertEquals(2, syncOps.barriers.size());
        assertEquals(1, pool.totalAllocations(), "第二次写入复用归还的池内快照，无新分配");
        assertEquals(1, pool.pooledBufferCount(), "借还平衡，无快照泄漏");

        queue.swapFrames();
        syncRenderThread();
        assertEquals(3, syncOps.created.size());
        assertEquals(List.of(syncOps.created.get(0)), syncOps.deleted,
                "发布新 fence 时必须隔代删除上上一代 fence");
        assertTrue(token.isValid());
    }

    @Test
    void unregisteredMappingIsRejectedWithoutEnqueueing() {
        FakeRenderQueue fake = new FakeRenderQueue();
        BridgeSupport.install(fake);
        BridgeSupport.syncOpsForTesting(syncOps);
        ByteBuffer target = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1, 2, 3, 4));

        assertEquals(0, fake.recorded.size(), "未登记映射的写入不得产生队列命令");
        assertTargetInts(target, 0, 0, 0, 0);
        assertNull(RealMappingRegistry.tokenFor(target));
        assertNull(MappedWriteBridgeImpl.frameEndMarker(0L), "写入被拒绝不得挂载通道");
    }

    @Test
    void invalidatedTokenSkipsWriteWithoutFailingFrame() {
        installRealQueue();
        ByteBuffer target = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        RealMappingRegistry.Token token = RealMappingRegistry.track(VBO_ID, target);
        assertNotNull(token);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        queue.submit(() -> {
            entered.countDown();
            awaitLatch(gate);
        });
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1, 2, 3, 4));
        queue.swapFrames();
        awaitLatch(entered);

        RealMappingRegistry.invalidateBuffer(VBO_ID);
        assertFalse(token.isValid(), "invalidate 必须立即使令牌失效");
        gate.countDown();
        syncRenderThread();

        assertTargetInts(target, 0, 0, 0, 0);
        assertTrue(syncOps.clientWaited.isEmpty(), "令牌复查先于 fence 等待，失效即短路");
        assertTrue(syncOps.barriers.isEmpty(), "跳过写入不得触碰内存屏障");
        assertEquals(1, BridgeSupport.pool().pooledBufferCount(), "跳过路径也必须归还快照");
        assertEquals(1, syncOps.created.size(), "帧尾 marker 仍正常发布 fence");
        assertDoesNotThrow(() -> queue.swapFramesAndSync());
    }

    @Test
    void fenceWaitTimeoutSkipsWriteAndKeepsTargetUntouched() {
        installRealQueue();
        ByteBuffer target = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        assertNotNull(RealMappingRegistry.track(VBO_ID, target));

        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1, 2, 3, 4));
        queue.swapFrames();
        syncRenderThread();
        assertTargetInts(target, 1, 2, 3, 4);
        assertEquals(1, syncOps.barriers.size());

        syncOps.clientWaitStatus = org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED;
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(5, 6, 7, 8));
        queue.swapFrames();
        syncRenderThread();
        assertDoesNotThrow(() -> queue.swapFramesAndSync(), "超时跳过是 warn-only，帧不得失败");

        assertTargetInts(target, 1, 2, 3, 4);
        assertEquals(1, syncOps.clientWaited.size(), "必须先做 fence 等待再判定超时");
        assertSame(syncOps.created.get(0), syncOps.clientWaited.get(0),
                "等待的必须是上一帧发布的 fence");
        assertEquals(1, syncOps.barriers.size(), "超时跳过不得再触发内存屏障");
        assertEquals(1, BridgeSupport.pool().pooledBufferCount(), "超时路径必须归还快照");
    }

    @Test
    void writeIsDeferredBySuspendWhenPreviousFrameMarkerLags() {
        installRealQueue();
        ByteBuffer target = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        assertNotNull(RealMappingRegistry.track(VBO_ID, target));

        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1, 1, 1, 1));
        queue.swapFrames();
        syncRenderThread();
        assertTargetInts(target, 1, 1, 1, 1);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        queue.submit(() -> {
            entered.countDown();
            awaitLatch(gate);
        });
        queue.swapFrames();
        AtomicBoolean suspended = new AtomicBoolean();
        queue.submit(() -> {
            if (suspended.compareAndSet(false, true)) {
                throw SuspendFrameException.INSTANCE;
            }
        });
        queue.swapFrames();
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(7, 7, 7, 7));
        queue.swapFrames();
        awaitLatch(entered);
        gate.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (syncOps.barriers.size() < 2 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        syncRenderThread();

        assertTrue(suspended.get(), "帧 2 的悬挂必须真实发生");
        assertTargetInts(target, 7, 7, 7, 7);
        assertEquals(4, syncOps.created.size(), "四帧帧尾各发布一个 fence");
        assertEquals(List.of("sync-3"), syncOps.clientWaited);
        assertTrue(syncOps.events.indexOf("fenceSync:sync-3") < syncOps.events.indexOf("clientWait:sync-3"),
                "writeD 必须在其等待的 fence 发布之后才执行");
        assertEquals(2, syncOps.barriers.size(), "两次成功落笔各一次屏障");
        assertEquals(List.of("sync-1", "sync-2"), syncOps.deleted, "fence 隔代删除");
        assertEquals(1, BridgeSupport.pool().pooledBufferCount(), "悬挂重试路径不得重复归还快照");
        assertEquals(1, BridgeSupport.pool().totalAllocations(), "快照复用池内缓冲");
    }

    @Test
    void frameEndMarkerIsNullUntilArmed() {
        assertNull(MappedWriteBridgeImpl.frameEndMarker(42L), "未挂载时帧尾零开销");

        installRealQueue();
        ByteBuffer target = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        assertNotNull(RealMappingRegistry.track(VBO_ID, target));
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1));

        GlCommand marker = MappedWriteBridgeImpl.frameEndMarker(7L);
        assertNotNull(marker, "挂载后帧尾必须插入 fence 标记");

        queue.swapFrames();
        syncRenderThread();
        assertTargetInts(target, 1, 0, 0, 0);
    }
}
