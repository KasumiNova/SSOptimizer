package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RenderSegment} 的段缓冲语义：局部提交序号、提交封存 fail-fast、
 * 池化重置（清空命令、解除封存、序号跨帧延续）、多写者并发互斥（aux 线程
 * 与主线程并发写串行段的回归护栏——桥状态污染/文本腐坏根因修复）。
 */
class RenderSegmentTest {

    @Test
    void addAppendsAndIncrementsCommitSeq() {
        RenderSegment segment = new RenderSegment();
        assertEquals(0, segment.commandCount());
        int before = segment.commitSeq();

        segment.add(() -> {
        });
        segment.add(() -> {
        });

        assertEquals(2, segment.commandCount());
        assertEquals(before + 2, segment.commitSeq(), "每次 add 递增段局部提交序号");
    }

    @Test
    void sealedSegmentRejectsLateWrite() {
        RenderSegment segment = new RenderSegment();
        segment.add(() -> {
        });
        segment.seal();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> segment.add(() -> {
                }), "封存后的迟到写入必须 fail-fast");
        assertTrue(thrown.getMessage().contains("封存"), "异常消息应指出封存不变量");
    }

    @Test
    void resetClearsCommandsAndUnsealsButKeepsSeqMonotonic() {
        RenderSegment segment = new RenderSegment();
        segment.add(() -> {
        });
        int seqBefore = segment.commitSeq();
        segment.seal();
        segment.reset();

        assertEquals(0, segment.commandCount(), "reset 清空命令列表");
        assertEquals(seqBefore, segment.commitSeq(), "reset 不归零序号（去重只比较相等性）");
        segment.add(() -> {
        });
        assertEquals(seqBefore + 1, segment.commitSeq(), "序号跨重置延续单调");
    }

    @Test
    void concurrentMultiWriterAddKeepsCountAndSeqConsistent() throws InterruptedException {
        // aux 线程与主线程并发写同一段（BoxUtil 后台 GL 线程劫持场景）：
        // 每条 add 必须恰好入列一条且 seq 恰好 +1——修复前 ArrayList 并发 add
        // 丢命令、commitSeq++ 丢失递增，dedup 据此错误吞掉状态命令（文本腐坏）。
        RenderSegment segment = new RenderSegment();
        int writers = 8;
        int addsPerWriter = 2_000;
        Thread[] threads = new Thread[writers];
        for (int i = 0; i < writers; i++) {
            threads[i] = new Thread(() -> {
                for (int n = 0; n < addsPerWriter; n++) {
                    segment.add(() -> {
                    });
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        int expected = writers * addsPerWriter;
        assertEquals(expected, segment.commandCount(), "并发写入下命令列表一条不丢");
        assertEquals(expected, segment.commitSeq(), "并发写入下提交序号一次不丢");
    }
}
