package github.kasuminova.ssoptimizer.common.render.queue;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RenderQueueImpl} 帧序号与帧尾标记钩子（{@code frameEndMarkerHook}）的行为验证：
 * 帧序单调递增、{@code lastSubmittedSequence} 与已提交帧一致、marker 追加在帧尾执行、
 * hook 返回 null 时零开销不炸。
 */
class RenderQueueSequenceTest {

    private RenderQueueImpl queue;

    @AfterEach
    void tearDown() {
        // 静态 hook 必须复位：进程级单例语义，残留会串扰其他用例
        RenderQueueImpl.frameEndMarkerHook(null);
        if (queue != null) {
            queue.shutdown();
        }
        RenderThreadMode.resetLoadingFinishedForTesting();
    }

    @Test
    void noSubmissionReportsMinusOne() {
        queue = new RenderQueueImpl();
        assertEquals(-1, queue.lastSubmittedSequence(), "无提交时已提交帧序号为 -1");
    }

    @Test
    void frameEndMarkerExecutesAtFrameTailWithMonotonicSequences() {
        queue = new RenderQueueImpl();
        List<String> events = new CopyOnWriteArrayList<>();
        List<Long> hookSeqs = new CopyOnWriteArrayList<>();
        RenderQueueImpl.frameEndMarkerHook(seq -> {
            hookSeqs.add(seq);
            return () -> events.add("marker-" + seq);
        });

        queue.submit(() -> events.add("cmd-0"));
        queue.swapFrames(); // 提交帧 0
        assertEquals(0, queue.lastSubmittedSequence(), "提交帧 0 后已提交序号为 0");

        queue.submit(() -> events.add("cmd-1"));
        queue.swapFramesAndSync(); // 提交帧 1，等待帧 0 执行完
        assertEquals(1, queue.lastSubmittedSequence());
        // 渲染线程可能已提前执行后续帧，只断言确定成立的事实：帧 0 已执行完、
        // 且 marker 在同帧命令之后（帧尾）
        assertTrue(events.contains("marker-0"), "等待帧 0 完成后其 marker 必然已执行");
        assertTrue(events.indexOf("cmd-0") < events.indexOf("marker-0"),
                "帧 0 的 marker 必须追加在帧尾（本帧全部命令之后）执行");

        queue.swapFramesAndSync(); // 提交帧 2，等待帧 1
        assertTrue(events.contains("marker-1"));
        assertTrue(events.indexOf("cmd-1") < events.indexOf("marker-1"),
                "帧 1 的 marker 同样在帧尾");

        queue.swapFramesAndSync(); // 提交帧 3，等待帧 2
        assertTrue(events.contains("marker-2"), "空帧同样携带帧尾 marker");
        assertEquals(List.of(0L, 1L, 2L, 3L), hookSeqs, "帧序号按提交顺序单调递增");
        assertEquals(3, queue.lastSubmittedSequence(), "lastSubmittedSequence 与已提交帧一致");
    }

    @Test
    void nullMarkerFromHookSkipsFrameTail() {
        queue = new RenderQueueImpl();
        AtomicInteger hookCalls = new AtomicInteger();
        RenderQueueImpl.frameEndMarkerHook(seq -> {
            hookCalls.incrementAndGet();
            return null;
        });

        AtomicBoolean ran = new AtomicBoolean();
        queue.submit(() -> ran.set(true));
        queue.swapFrames();
        queue.swapFramesAndSync();

        assertTrue(ran.get(), "hook 返回 null 时帧命令照常执行");
        assertTrue(hookCalls.get() >= 2, "每帧提交都必须询问 hook");
        assertEquals(1, queue.lastSubmittedSequence(), "无 marker 不影响帧序号推进");
        assertDoesNotThrow(() -> queue.swapFramesAndSync(), "hook 返回 null 不炸");
    }
}
