package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.AuxOriginCommand;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * bridge 录制侧的 aux 命令包装路由：aux-context 生产者线程（非主录制线程、
 * 无并行段绑定）的一切落帧命令（普通命令、顶点流落帧、状态命令）必须包装为
 * {@link AuxOriginCommand}，供渲染线程执行循环围进状态围栏；主录制线程与
 * 编排器 worker（段绑定）不包装。
 */
class AuxCommandWrapTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        // 主录制线程判定锚定到本测试线程：类初始化捕获在测试 JVM 内不确定
        queue = new FakeRenderQueue();
        github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl.captureMainThreadForTesting();
        BridgeSupport.install(queue);
        BridgeSupport.stateDedupEnabled = true;
    }

    @AfterEach
    void tearDown() {
        BridgeSupport.uninstall();
    }

    @Test
    void auxThreadCommandsAreWrapped() throws Exception {
        GlCommand command = () -> {
        };
        runOnAuxThread(() -> BridgeSupport.enqueue(command));

        assertEquals(1, queue.recorded.size());
        GlCommand recorded = queue.recorded.get(0);
        assertInstanceOf(AuxOriginCommand.class, recorded, "aux 线程命令必须包装来源标记");
        assertSame(command, ((AuxOriginCommand) recorded).delegate(), "包装不得改变命令本体");
    }

    @Test
    void mainThreadCommandsAreNotWrapped() {
        GlCommand command = () -> {
        };
        BridgeSupport.enqueue(command);

        assertEquals(1, queue.recorded.size());
        assertSame(command, queue.recorded.get(0), "主录制线程命令不得包装");
    }

    @Test
    void boundWorkerCommandsAreNotWrapped() throws Exception {
        int base = queue.frame.reserveSegments(1);
        RenderSegment assigned = queue.frame.segment(base);
        GlCommand command = () -> {
        };

        runOnAuxThread(() -> {
            BridgeSupport.bindSegment(assigned);
            try {
                BridgeSupport.enqueue(command);
            } finally {
                BridgeSupport.unbindSegment();
            }
        });

        assertTrue(queue.recorded.isEmpty(), "worker 段绑定期间录制直写绑定段，不经过帧临界区");
        assertEquals(1, assigned.commandCount(), "worker 段内命令不得包装（单条直写）");
    }

    @Test
    void auxThreadVertexStreamFlushIsWrapped() throws Exception {
        // immediate 顶点流在 glEnd 落帧：aux 线程的流段命令同样是污染源，必须包装
        runOnAuxThread(() -> {
            GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
            GL11.glVertex2f(0f, 0f);
            GL11.glVertex2f(1f, 0f);
            GL11.glVertex2f(1f, 1f);
            GL11.glVertex2f(0f, 1f);
            GL11.glEnd();
        });

        assertEquals(1, queue.recorded.size(), "glEnd 必须把顶点流落帧为一条命令");
        assertInstanceOf(AuxOriginCommand.class, queue.recorded.get(0),
                "aux 线程的顶点流落帧命令必须包装来源标记");
    }

    @Test
    void auxThreadStateCommandsSkipDedupAndAreWrapped() throws Exception {
        // aux 线程的状态命令不参与去重（相邻性判据在并发录制下失真），逐条包装落帧
        runOnAuxThread(() -> {
            BridgeSupport.enqueueState(StateDedup.TYPE_ENABLE, 1, 0, 0, 0, () -> {
            });
            BridgeSupport.enqueueState(StateDedup.TYPE_ENABLE, 1, 0, 0, 0, () -> {
            });
        });

        assertEquals(2, queue.recorded.size(), "aux 线程的状态命令不得去重（判据失真，见 enqueueState 注释）");
        assertInstanceOf(AuxOriginCommand.class, queue.recorded.get(0));
        assertInstanceOf(AuxOriginCommand.class, queue.recorded.get(1));
    }

    /** 在独立线程上运行并 join（aux 生产者上下文；异常向外传播）。 */
    private static void runOnAuxThread(ThrowingRunnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        worker.start();
        worker.join();
        if (failure.get() != null) {
            throw new AssertionError("aux 线程内断言失败", failure.get());
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
