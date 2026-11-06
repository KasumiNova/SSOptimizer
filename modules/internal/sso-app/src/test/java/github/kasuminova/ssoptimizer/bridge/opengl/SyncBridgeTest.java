package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.SignalFenceCommand;
import github.kasuminova.ssoptimizer.common.render.queue.SuspendFrameException;
import github.kasuminova.ssoptimizer.common.render.queue.WaitFenceCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SyncBridgeTest {

    private static final class FakeSyncOps implements RealSyncOps {
        final List<Object> created = new ArrayList<>();
        final List<Object> waited = new ArrayList<>();
        final List<Object> clientWaited = new ArrayList<>();
        final List<Object> deleted = new ArrayList<>();
        private int nextToken;

        @Override
        public Object fenceSync(final int condition, final int flags) {
            Object token = "sync-" + (++nextToken);
            created.add(token);
            return token;
        }

        @Override
        public void waitSync(final Object sync, final int flags, final long timeout) {
            waited.add(sync);
        }

        @Override
        public int clientWaitSync(final Object sync, final int flags, final long timeout) {
            clientWaited.add(sync);
            return org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
        }

        @Override
        public void deleteSync(final Object sync) {
            deleted.add(sync);
        }

        @Override
        public void memoryBarrier(final int barriers) {
        }
    }

    private static final class FakeRealSharedContext implements SharedDrawable.RealSharedContext {
        int makeCurrentCount;
        int releaseCount;
        int destroyCount;

        @Override
        public void makeCurrent() {
            makeCurrentCount++;
        }

        @Override
        public void releaseContext() {
            releaseCount++;
        }

        @Override
        public void destroy() {
            destroyCount++;
        }
    }

    private FakeRenderQueue queue;
    private FakeSyncOps syncOps;
    private SharedDrawable.RealSharedContextFactory savedFactory;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL32.install(queue);
        syncOps = new FakeSyncOps();
        BridgeSupport.syncOpsForTesting(syncOps);
        savedFactory = SharedDrawable.realContextFactory;
    }

    @AfterEach
    void tearDown() {
        SharedDrawable.realContextFactoryForTesting(savedFactory);
        GL32.uninstall();
    }

    @Test
    void fenceSyncSignalCommandCreatesAndAttachesRealSync() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertNotNull(sync);
        assertFalse(sync.isDeleted());
        assertNull(sync.realSync(), "信号命令执行前真实 sync 尚未附着");
        assertEquals(1, queue.recorded.size());
        assertInstanceOf(SignalFenceCommand.class, queue.recorded.get(0));
        assertFalse(sync.fence().isSignaled());
        queue.recorded.get(0).execute();
        assertTrue(sync.fence().isSignaled());
        Object real = sync.realSync();
        assertNotNull(real, "信号命令执行后真实 sync 已附着");
        assertEquals(1, syncOps.created.size());
        assertSame(real, syncOps.created.get(0));
    }

    @Test
    void waitSyncRecordedBeforeSignalSuspendsInsteadOfBlocking() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL32.glWaitSync(sync, 0, org.lwjgl.opengl.GL32.GL_TIMEOUT_IGNORED);
        assertEquals(2, queue.recorded.size());
        assertInstanceOf(WaitFenceCommand.class, queue.recorded.get(1));

        assertThrows(SuspendFrameException.class, () -> queue.recorded.get(1).execute(),
                "fence 未 signal 时 wait 命令必须悬挂而非阻塞");
        assertTrue(syncOps.waited.isEmpty(), "悬挂时不触碰真实 GL");
        queue.recorded.get(0).execute();
        assertDoesNotThrow(() -> queue.recorded.get(1).execute(), "signal 执行后 wait 必须放行");
        assertEquals(1, syncOps.waited.size(), "放行后追加真实 glWaitSync 建立跨上下文 GPU 序");
        assertSame(sync.realSync(), syncOps.waited.get(0));
    }

    @Test
    void deleteSyncBeforeSignalIsDeletedOnCreation() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL32.glDeleteSync(sync);
        assertTrue(sync.isDeleted());
        GL32.glDeleteSync(sync);
        assertTrue(sync.isDeleted(), "重复删除幂等");
        assertEquals(1, queue.recorded.size(), "真实 sync 未附着时 delete 不产生队列命令");
        queue.recorded.get(0).execute();
        assertEquals(1, syncOps.created.size());
        assertEquals(1, syncOps.deleted.size(), "信号命令发现句柄已删除，随建随删");
        assertSame(syncOps.created.get(0), syncOps.deleted.get(0));
    }

    @Test
    void deleteSyncAfterSignalEnqueuesRealDeletion() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        queue.recorded.get(0).execute();
        GL32.glDeleteSync(sync);
        assertTrue(sync.isDeleted());
        assertEquals(2, queue.recorded.size(), "已附着真实 sync 的删除入队一条命令");
        queue.recorded.get(1).execute();
        assertEquals(1, syncOps.deleted.size());
        assertSame(sync.realSync(), syncOps.deleted.get(0));
    }

    @Test
    void clientWaitSyncDispatchesRealWaitViaUncountedChannel() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        queue.recorded.get(0).execute();
        GL32.glClientWaitSync(sync, 0, 1_000_000_000L);
        assertEquals(1, queue.uncountedBlockingTasks.size(), "真实 clientWait 走不计数阻塞通道");
        assertTrue(syncOps.clientWaited.isEmpty());
        queue.uncountedBlockingTasks.get(0).run();
        assertEquals(1, syncOps.clientWaited.size());
        assertSame(sync.realSync(), syncOps.clientWaited.get(0));
    }

    @Test
    void sharedDrawableCreatesRealContextAndMarksAuxNative() throws Exception {
        FakeRealSharedContext ctx = new FakeRealSharedContext();
        SharedDrawable.realContextFactoryForTesting(() -> ctx);
        queue.getHandler = callable -> {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        SharedDrawable drawable = new SharedDrawable(null);
        assertFalse(drawable.isCurrent());
        drawable.makeCurrent();
        assertTrue(drawable.isCurrent(), "makeCurrent 登记当前线程");
        assertEquals(1, ctx.makeCurrentCount);
        assertEquals(1, queue.getCallCount, "真实上下文创建经渲染线程阻塞通道串行");
        assertTrue(BridgeSupport.recordingContext().auxNative,
                "makeCurrent 后本线程标记为 aux 原生线程");

        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean otherThreadCurrent = new AtomicBoolean(true);
        Thread other = new Thread(() -> {
            otherThreadCurrent.set(drawable.isCurrent());
            entered.countDown();
        });
        other.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        other.join(2000);
        assertFalse(otherThreadCurrent.get());

        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertTrue(sync.fence().isSignaled(), "aux 产 fence 预 signal（等待方读真实 sync）");
        assertNotNull(sync.realSync(), "aux 产 fence 创建即附着真实 sync");
        assertEquals(1, syncOps.created.size());
        assertEquals(0, queue.recorded.size(), "aux 线程不产生队列命令");
        GL32.glDeleteSync(sync);
        assertEquals(1, syncOps.deleted.size(), "aux 线程 delete 原生直执");
        assertEquals(0, queue.recorded.size());

        drawable.releaseContext();
        assertFalse(drawable.isCurrent());
        assertFalse(BridgeSupport.recordingContext().auxNative, "release 复位 aux 标记");
        assertEquals(1, ctx.releaseCount);

        drawable.makeCurrent();
        assertEquals(2, ctx.makeCurrentCount);
        drawable.destroy();
        assertFalse(drawable.isCurrent());
        assertEquals(1, ctx.destroyCount);
        assertThrows(IllegalStateException.class, drawable::makeCurrent, "销毁后拒绝 makeCurrent");
    }
}
