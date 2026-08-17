package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.SignalFenceCommand;
import github.kasuminova.ssoptimizer.common.render.queue.WaitFenceCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GL32 fence 族与 BoxUtil 门面（GLSync/Drawable/SharedDrawable）的行为验证。
 * fence 命令体是纯 Java 会合逻辑（SignalFenceCommand/WaitFenceCommand 不触碰
 * 真实 GL），可直接执行验证乱序会合语义。
 */
class SyncBridgeTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL32.install(queue);
    }

    @AfterEach
    void tearDown() {
        GL32.uninstall();
    }

    @Test
    void fenceSyncReturnsHandleLinkedToRecordedSignalCommand() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertNotNull(sync);
        assertFalse(sync.isDeleted());
        assertEquals(1, queue.recorded.size());
        assertInstanceOf(SignalFenceCommand.class, queue.recorded.get(0));
        // 句柄与信号命令携带同一 fence：执行信号命令后 wait 侧的 fence 完成
        assertFalse(sync.fence().isSignaled());
        queue.recorded.get(0).execute();
        assertTrue(sync.fence().isSignaled());
    }

    @Test
    void waitSyncRecordedBeforeSignalStillRendezvous() throws InterruptedException {
        // 乱序录制场景：wait 命令先入队（aux-context 生产者线程抢先）
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL32.glWaitSync(sync, 0, org.lwjgl.opengl.GL32.GL_TIMEOUT_IGNORED);
        assertEquals(2, queue.recorded.size());
        assertInstanceOf(WaitFenceCommand.class, queue.recorded.get(1));

        // 先执行 wait（渲染线程乱序执行到的情形）：阻塞直至 signal 执行
        AtomicBoolean waitDone = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            queue.recorded.get(1).execute();
            waitDone.set(true);
        });
        waiter.start();
        Thread.sleep(50);
        assertFalse(waitDone.get(), "fence 未完成时 wait 命令必须阻塞");
        queue.recorded.get(0).execute();
        waiter.join(2000);
        assertTrue(waitDone.get(), "signal 执行后 wait 必须放行");
    }

    @Test
    void deleteSyncMarksHandleInvalid() {
        GLSync sync = GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL32.glDeleteSync(sync);
        assertTrue(sync.isDeleted());
        GL32.glDeleteSync(sync);
        assertTrue(sync.isDeleted(), "重复删除幂等");
        assertEquals(1, queue.recorded.size(), "delete 不产生队列命令");
    }

    @Test
    void sharedDrawableFoldsAuxContextIntoRegistration() throws Exception {
        SharedDrawable drawable = new SharedDrawable(null);
        assertFalse(drawable.isCurrent());
        drawable.makeCurrent();
        assertTrue(drawable.isCurrent(), "makeCurrent 登记当前线程");

        // 其他线程视角：未登记
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean otherThreadCurrent = new AtomicBoolean(true);
        Thread other = new Thread(() -> {
            try {
                otherThreadCurrent.set(drawable.isCurrent());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            entered.countDown();
        });
        other.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        other.join(2000);
        assertFalse(otherThreadCurrent.get());

        drawable.releaseContext();
        assertFalse(drawable.isCurrent());
        drawable.makeCurrent();
        drawable.destroy();
        assertFalse(drawable.isCurrent());
        assertThrows(IllegalStateException.class, drawable::makeCurrent, "销毁后拒绝 makeCurrent");
        assertEquals(0, queue.recorded.size(), "折叠设计下 drawable 操作不产生任何 GL 命令");
    }
}
