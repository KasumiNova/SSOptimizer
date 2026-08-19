package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * aux 游程状态机（{@link RenderQueueImpl} 执行循环 + {@link AuxOriginCommand} 标记
 * + {@link AuxRunFence}）的配对语义验证：连续 aux 命令构成一个游程，enter/exit
 * 严格配对包住游程；悬挂 requeue 与命令异常都必须先 exit 恢复游戏侧状态。
 * <p>
 * 命令体与围栏都是计数/记录桩，不触碰真实 GL——本测试只验证状态机与配对逻辑。
 */
class AuxRunFenceStateMachineTest {

    private RenderQueueImpl queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
        RenderQueueImpl.installAuxRunFence(AuxRunFence.NOOP);
        RenderQueueImpl.resetListCompileStateForTesting();
    }

    /** 记录事件的假围栏：与命令执行事件写入同一列表以验证交错顺序。 */
    private static final class RecordingFence implements AuxRunFence {
        private final List<String> events;

        private RecordingFence(List<String> events) {
            this.events = events;
        }

        @Override
        public void enter() {
            events.add("enter");
        }

        @Override
        public void exit() {
            events.add("exit");
        }
    }

    private RenderQueueImpl startQueue(List<String> events) {
        queue = new RenderQueueImpl();
        RenderQueueImpl.installAuxRunFence(new RecordingFence(events));
        return queue;
    }

    /** 提交一帧并等待其完成（swap 两次：第二次 sync 等待的正是本帧）。 */
    private void submitFrameAndSync() {
        queue.swapFrames();
        queue.swapFramesAndSync();
    }

    /** 悬挂续跑不关联帧 Future，轮询等待事件数收敛。 */
    private static void awaitEventCount(List<String> events, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (events.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, events.size(), "等待悬挂续跑执行收敛超时，事件=" + events);
    }

    @Test
    void contiguousAuxCommandsFormSingleFencedRun() {
        List<String> events = new CopyOnWriteArrayList<>();
        startQueue(events);

        queue.submit(() -> events.add("main-1"));
        queue.submit(new AuxOriginCommand(() -> events.add("aux-1")));
        queue.submit(new AuxOriginCommand(() -> events.add("aux-2")));
        queue.submit(() -> events.add("main-2"));
        submitFrameAndSync();

        assertEquals(
                List.of("main-1", "enter", "aux-1", "aux-2", "exit", "main-2"),
                events,
                "连续 aux 命令必须围成一个游程：enter 在首个 aux 前，exit 在其后首个非 aux 命令前");
    }

    @Test
    void separateAuxRunsInOneFrameAreFencedIndividually() {
        List<String> events = new CopyOnWriteArrayList<>();
        startQueue(events);

        queue.submit(new AuxOriginCommand(() -> events.add("aux-1")));
        queue.submit(() -> events.add("main-1"));
        queue.submit(new AuxOriginCommand(() -> events.add("aux-2")));
        submitFrameAndSync();

        assertEquals(
                List.of("enter", "aux-1", "exit", "main-1", "enter", "aux-2", "exit"),
                events,
                "被游戏命令隔断的两段 aux 命令必须各自独立围栏");
    }

    @Test
    void suspendAtNonAuxCommandEndsRunAtCommandBoundary() throws InterruptedException {
        List<String> events = new CopyOnWriteArrayList<>();
        startQueue(events);
        AtomicBoolean thrown = new AtomicBoolean(false);

        queue.submit(new AuxOriginCommand(() -> events.add("aux-1")));
        // 只悬挂一次的控制流命令：首次执行抛 SuspendFrameException 触发 requeue
        queue.submit(() -> {
            events.add("suspend-gate");
            if (thrown.compareAndSet(false, true)) {
                throw SuspendFrameException.INSTANCE;
            }
        });
        queue.submit(new AuxOriginCommand(() -> events.add("aux-2")));
        queue.swapFrames();

        // 首趟：enter → aux-1 → 遇非 aux 的 gate，游程在命令边界 exit → gate 悬挂
        // → requeue；续跑：gate（无围栏动作）→ enter → aux-2 → exit
        awaitEventCount(events, 8);
        assertEquals(
                List.of("enter", "aux-1", "exit", "suspend-gate",
                        "suspend-gate", "enter", "aux-2", "exit"),
                events,
                "游程必须在非 aux 命令（含悬挂点）边界 exit，续跑遇后续 aux 命令重新 enter");
    }

    @Test
    void suspendInsideAuxRunExitsFenceBeforeRequeue() throws InterruptedException {
        List<String> events = new CopyOnWriteArrayList<>();
        startQueue(events);
        AtomicBoolean thrown = new AtomicBoolean(false);

        queue.submit(new AuxOriginCommand(() -> events.add("aux-1")));
        // aux 命令自身悬挂：finally 兜底必须先 exit 再 requeue，不能把打开的快照
        // 状态带进续跑间隙的其他任务（getter 必须读到恢复后的游戏状态）
        queue.submit(new AuxOriginCommand(() -> {
            events.add("aux-gate");
            if (thrown.compareAndSet(false, true)) {
                throw SuspendFrameException.INSTANCE;
            }
        }));
        queue.submit(new AuxOriginCommand(() -> events.add("aux-2")));
        queue.swapFrames();

        awaitEventCount(events, 8);
        assertEquals(
                List.of("enter", "aux-1", "aux-gate", "exit",
                        "enter", "aux-gate", "aux-2", "exit"),
                events,
                "aux 游程内悬挂必须先 exit 再 requeue，续跑重新 enter 完成余下 aux 命令");
    }

    @Test
    void auxCommandsInsideListCompileWindowAreDeferredUntilWindowCloses() {
        List<String> events = new CopyOnWriteArrayList<>();
        startQueue(events);

        queue.submit(() -> {
            RenderQueueImpl.onListCompileBegin();
            events.add("begin-list");
        });
        queue.submit(new AuxOriginCommand(() -> events.add("aux-1")));
        queue.submit(new AuxOriginCommand(() -> events.add("aux-2")));
        queue.submit(() -> events.add("main-1"));
        queue.submit(() -> {
            RenderQueueImpl.onListCompileEnd();
            events.add("end-list");
        });
        queue.submit(() -> events.add("main-2"));
        submitFrameAndSync();

        assertEquals(
                List.of("begin-list", "main-1", "end-list", "enter", "aux-1", "aux-2", "exit", "main-2"),
                events,
                "编译窗口内的 aux 命令必须延迟到窗口关闭后作为一个游程补执行（不被编译进列表）");
    }

    @Test
    void suspendInsideListCompileWindowKeepsDeferredCommands() throws InterruptedException {
        List<String> events = new CopyOnWriteArrayList<>();
        startQueue(events);
        AtomicBoolean thrown = new AtomicBoolean(false);

        queue.submit(() -> {
            RenderQueueImpl.onListCompileBegin();
            events.add("begin-list");
        });
        queue.submit(new AuxOriginCommand(() -> events.add("aux-1")));
        // 编译窗口内悬挂：延迟队列必须跨 requeue 留存，续跑关窗后补执行
        queue.submit(() -> {
            events.add("gate");
            if (thrown.compareAndSet(false, true)) {
                throw SuspendFrameException.INSTANCE;
            }
        });
        queue.submit(() -> {
            RenderQueueImpl.onListCompileEnd();
            events.add("end-list");
        });
        queue.submit(new AuxOriginCommand(() -> events.add("aux-2")));
        queue.swapFrames();

        awaitEventCount(events, 8);
        assertEquals(
                List.of("begin-list", "gate", "gate", "end-list", "enter", "aux-1", "aux-2", "exit"),
                events,
                "编译窗口内悬挂时延迟的 aux 命令不得丢失，续跑关窗后与后续 aux 命令同一游程补执行");
    }

    @Test
    void commandExceptionStillExitsFence() {
        List<String> events = new CopyOnWriteArrayList<>();
        startQueue(events);

        queue.submit(new AuxOriginCommand(() -> events.add("aux-1")));
        queue.submit(new AuxOriginCommand(() -> {
            events.add("aux-boom");
            throw new IllegalStateException("模拟 aux 命令执行失败");
        }));
        queue.submit(() -> events.add("main-unreachable"));
        queue.swapFrames();
        // 帧执行失败的异常随帧 Future 在 sync 时重抛；围栏必须先于异常传播恢复状态
        assertThrows(IllegalStateException.class, () -> queue.swapFramesAndSync());

        assertEquals(
                List.of("enter", "aux-1", "aux-boom", "exit"),
                events,
                "aux 命令异常中断时 finally 必须兜底 exit，且其后命令被丢弃");
    }
}
