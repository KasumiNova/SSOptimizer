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

/**
 * {@link MappedWriteBridgeImpl} 有序映射写入通道的行为验证。
 * <p>
 * 驱动方式：真实 {@link RenderQueueImpl}（{@code arm()} 初始化需读取真实队列的
 * 已提交帧序号，FakeRenderQueue 不适用）+ 帧尾标记钩子直连
 * {@link MappedWriteBridgeImpl#frameEndMarker(long)}；渲染线程上的真实 GL sync
 * 操作注入 {@link FakeSyncOps} 记录。测试线程经「阻塞门闩命令 + get 同步任务」
 * 制造确定性时序：门闩保证「提交在先、执行在后」，get 任务排在全部已提交工作
 * 之后，返回即断言点所见状态为渲染线程已执行完的稳定态。
 */
class MappedWriteBridgeImplTest {
    private static final int VBO_ID = 42;

    /** 记录调用的假真实 GL sync 操作：句柄用字符串令牌，可断言身份传递与跨调用点顺序。
     *  记录列表用 CopyOnWrite：写入发生在渲染线程，断言线程可能在其执行途中读取。 */
    private static final class FakeSyncOps implements RealSyncOps {
        final List<Object> created = new CopyOnWriteArrayList<>();
        final List<Object> clientWaited = new CopyOnWriteArrayList<>();
        final List<Object> deleted = new CopyOnWriteArrayList<>();
        final List<Integer> barriers = new CopyOnWriteArrayList<>();
        /** 全局事件流（fence 发布与 clientWait 的相对顺序断言用）。 */
        final List<String> events = new CopyOnWriteArrayList<>();
        /** clientWaitSync 的桩返回值（默认真即满足）。 */
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
            // 有序写入通道不走服务端 waitSync，空实现即可
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

    /** 安装真实队列 + 帧尾标记钩子 + 假 sync 操作 + 全新快照池（池计数断言从零起步）。 */
    private void installRealQueue() {
        queue = new RenderQueueImpl();
        BridgeSupport.install(queue);
        RenderQueueImpl.frameEndMarkerHook(MappedWriteBridgeImpl::frameEndMarker);
        BridgeSupport.syncOpsForTesting(syncOps);
        BridgeSupport.resetPoolForTesting();
    }

    /** 渲染线程同步点：get 任务排在全部已提交工作之后，返回即此前命令已执行完。 */
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

    /** 构造 nativeOrder 的 int 源缓冲（与 bridge 录制侧的真实数据来源同形态）。 */
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

        // 帧 0：首次写入触发挂载；帧尾 marker 执行时发布首个 fence
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

        // 帧 1：写入命令等待帧 0 发布的 fence 后落笔
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(5, 6, 7, 8));
        queue.swapFrames();
        syncRenderThread();

        assertTargetInts(target, 5, 6, 7, 8);
        assertEquals(List.of(syncOps.created.get(0)), syncOps.clientWaited,
                "第二帧写入必须等待上一帧帧尾 fence");
        assertEquals(2, syncOps.barriers.size());
        assertEquals(1, pool.totalAllocations(), "第二次写入复用归还的池内快照，无新分配");
        assertEquals(1, pool.pooledBufferCount(), "借还平衡，无快照泄漏");

        // 帧 2（空帧）：marker 发布第三个 fence，隔代删除第一个
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

        // 门闩命令把渲染线程钉在写入命令之前，保证 invalidate 先于命令执行发生
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
        // 跳过写入是 warn-only 语义：帧正常完成，不向主线程传播失败
        // （该调用会再提交一帧、多发一个 fence，故放在计数断言之后）
        assertDoesNotThrow(() -> queue.swapFramesAndSync());
    }

    @Test
    void fenceWaitTimeoutSkipsWriteAndKeepsTargetUntouched() {
        installRealQueue();
        ByteBuffer target = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        assertNotNull(RealMappingRegistry.track(VBO_ID, target));

        // 帧 0：建立帧尾 fence（sync-1）并落入基线内容
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1, 2, 3, 4));
        queue.swapFrames();
        syncRenderThread();
        assertTargetInts(target, 1, 2, 3, 4);
        assertEquals(1, syncOps.barriers.size());

        // 帧 1：fence 等待超时 → 跳过写入
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

        // 帧 0：写入基线并发布首个 fence（fullyExecutedSeq 推进到 0）
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(1, 1, 1, 1));
        queue.swapFrames();
        syncRenderThread();
        assertTargetInts(target, 1, 1, 1, 1);

        // 门闩钉住渲染线程：保证帧 1/2/3 全部提交后才开始执行，时序确定
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        queue.submit(() -> {
            entered.countDown();
            awaitLatch(gate);
        });
        queue.swapFrames(); // 帧 1 = [门闩, marker1]
        // 帧 2：首命令首次执行时悬挂一次，模拟「上一帧悬挂、帧尾标记随续跑任务晚到」
        AtomicBoolean suspended = new AtomicBoolean();
        queue.submit(() -> {
            if (suspended.compareAndSet(false, true)) {
                throw SuspendFrameException.INSTANCE;
            }
        });
        queue.swapFrames(); // 帧 2 = [悬挂命令, marker2]
        // 帧 3：写入命令——帧 2 悬挂后其 marker 排在帧 3 任务之后，writeD 首次执行时
        // fullyExecutedSeq(=1) < frameSeq(=3)-1，必须悬挂重排到帧 2 续跑之后
        MappedWriteBridgeImpl.get().submitOrderedWrite(target, sourceOf(7, 7, 7, 7));
        queue.swapFrames(); // 帧 3 = [writeD, marker3]
        awaitLatch(entered);
        gate.countDown();
        // 悬挂续跑任务由渲染线程自行 requeue，提交侧无法在其后排队同步任务：
        // 自旋至 writeD 落笔（第二次屏障），再经同步任务建立 happens-before 后断言
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (syncOps.barriers.size() < 2 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        syncRenderThread();

        assertTrue(suspended.get(), "帧 2 的悬挂必须真实发生");
        assertTargetInts(target, 7, 7, 7, 7);
        assertEquals(4, syncOps.created.size(), "四帧帧尾各发布一个 fence");
        // writeD 等待的是帧 2 marker 发布的 sync-3（而非其提交时刻最新的 sync-2）：
        // 证明它被帧序闸门悬挂、重排到帧 2 续跑（marker2 发布 sync-3）之后才落笔
        assertEquals(List.of("sync-3"), syncOps.clientWaited);
        assertTrue(syncOps.events.indexOf("fenceSync:sync-3") < syncOps.events.indexOf("clientWait:sync-3"),
                "writeD 必须在其等待的 fence 发布之后才执行");
        assertEquals(2, syncOps.barriers.size(), "两次成功落笔各一次屏障");
        assertEquals(List.of("sync-1", "sync-2"), syncOps.deleted, "fence 隔代删除");
        // 悬挂（SuspendFrameException）时命令会被续跑任务重试，快照所有权必须保留到
        // 真正落笔/跳过的那次执行；若悬挂路径也归还，同一实例会被重复入池（别名污染），
        // 此处表现为借还平衡后池计数虚高（2 而非 1）
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

        // 排空录制帧：写入命令执行、快照归还，避免静态状态残留影响其他用例
        queue.swapFrames();
        syncRenderThread();
        assertTargetInts(target, 1, 0, 0, 0);
    }
}
