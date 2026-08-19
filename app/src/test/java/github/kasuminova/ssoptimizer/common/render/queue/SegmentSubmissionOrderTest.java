package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分段模型与 {@link RenderQueueImpl} 的集成验证：提交时扁平化、
 * worker 并发录制跨线程可见性（编排器屏障 → 提交通道 happens-before）、
 * 回放执行序 = 段登记序。
 */
class SegmentSubmissionOrderTest {

    private RenderQueueImpl queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Test
    void replayOrderFollowsSegmentRegistrationAcrossConcurrentWorkers() throws Exception {
        queue = new RenderQueueImpl();
        List<String> executed = Collections.synchronizedList(new ArrayList<>());

        RenderFrame frame = queue.currentFrame();
        queue.submit(marker("serial-0", executed));
        int base = frame.reserveSegments(2);
        RenderSegment segA = frame.segment(base);
        RenderSegment segB = frame.segment(base + 1);

        // 两个 worker 并发写入各自段；刻意让 A 慢 B 快，完成序与登记序相反
        Thread workerA = new Thread(() -> {
            parkMillis(50);
            segA.add(marker("par-A", executed));
        });
        Thread workerB = new Thread(() -> segB.add(marker("par-B", executed)));
        workerA.start();
        workerB.start();
        // 编排器屏障：worker 全部 join 后才继续录制/提交
        workerA.join();
        workerB.join();

        frame.openNextSerialSegment();
        queue.submit(marker("serial-1", executed));

        queue.swapFramesAndSync(); // 提交本帧
        queue.swapFramesAndSync(); // 提交下一空帧并等本帧执行完

        assertEquals(List.of("serial-0", "par-A", "par-B", "serial-1"), executed,
                "回放执行序必须是段登记序，与 worker 完成先后无关");
    }

    @Test
    void singleSegmentFrameReplayIsUnchanged() {
        queue = new RenderQueueImpl();
        List<String> executed = Collections.synchronizedList(new ArrayList<>());

        queue.submit(marker("a", executed));
        queue.submit(marker("b", executed));
        queue.swapFramesAndSync();
        queue.swapFramesAndSync();

        assertEquals(List.of("a", "b"), executed, "非并行帧的行为与单列表时代一致");
    }

    private static GlCommand marker(String name, List<String> executed) {
        return () -> executed.add(name);
    }

    private static void parkMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
