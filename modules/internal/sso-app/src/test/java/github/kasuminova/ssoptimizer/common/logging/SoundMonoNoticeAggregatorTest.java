package github.kasuminova.ssoptimizer.common.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SoundMonoNoticeAggregator} 聚合语义测试（直调真实 record/flush 逻辑，
 * reporter 注入收集器断言汇总行）。
 */
class SoundMonoNoticeAggregatorTest {

    @Test
    void notMonoNoticesAggregateToSummaryAtBatchSize() {
        final List<String> reports = new ArrayList<>();
        final SoundMonoNoticeAggregator aggregator = new SoundMonoNoticeAggregator(reports::add);

        for (int i = 0; i < SoundMonoNoticeAggregator.BATCH_SIZE - 1; i++) {
            aggregator.record(String.format("Sound [sfx_%d] is NOT mono", i));
        }
        assertTrue(reports.isEmpty(), "未达批次阈值不得输出汇总");

        aggregator.record("Sound [sfx_last] is NOT mono");
        assertEquals(List.of("[SSOptimizer] " + SoundMonoNoticeAggregator.BATCH_SIZE
                + " sounds are NOT mono (console notices aggregated)"), reports);
    }

    @Test
    void uiMonoAndNotMonoCountedSeparately() {
        final List<String> reports = new ArrayList<>();
        final SoundMonoNoticeAggregator aggregator = new SoundMonoNoticeAggregator(reports::add);

        for (int i = 0; i < SoundMonoNoticeAggregator.BATCH_SIZE; i++) {
            aggregator.record(String.format("UI sound [ui_%d] is mono", i));
        }
        assertEquals(List.of("[SSOptimizer] " + SoundMonoNoticeAggregator.BATCH_SIZE
                + " UI sounds are mono (console notices aggregated)"), reports);
    }

    @Test
    void flushEmitsRemainingCountsBelowBatchSize() {
        final List<String> reports = new ArrayList<>();
        final SoundMonoNoticeAggregator aggregator = new SoundMonoNoticeAggregator(reports::add);

        aggregator.record("Sound [a] is NOT mono");
        aggregator.record("Sound [b] is NOT mono");
        aggregator.record("UI sound [c] is mono");
        aggregator.flush();

        assertEquals(List.of(
                "[SSOptimizer] 2 sounds are NOT mono (console notices aggregated)",
                "[SSOptimizer] 1 UI sounds are mono (console notices aggregated)"
        ), reports);

        aggregator.flush();
        assertEquals(2, reports.size(), "计数清空后重复 flush 不得再输出");
    }

    @Test
    void flushWithoutPendingCountsIsSilent() {
        final List<String> reports = new ArrayList<>();
        final SoundMonoNoticeAggregator aggregator = new SoundMonoNoticeAggregator(reports::add);

        aggregator.flush();
        assertTrue(reports.isEmpty(), "无累计计数时 flush 必须零输出");
    }
}
