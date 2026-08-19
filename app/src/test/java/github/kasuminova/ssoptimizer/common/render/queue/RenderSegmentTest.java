package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RenderSegment} 的单写者段缓冲语义：局部提交序号、提交封存 fail-fast、
 * 池化重置（清空命令、解除封存、序号跨帧延续）。
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
}
