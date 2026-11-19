package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnimatedFontSizeTest {
    @Test
    void repeatedBucketChangesEnablePreviewUntilSizeStabilizes() {
        var sizes = new AnimatedFontSize();
        assertFalse(sizes.observe(1, 0));
        assertFalse(sizes.observe(1.01f, 10));
        assertFalse(sizes.observe(1.2f, 20));
        assertTrue(sizes.observe(1.5f, 30));
        assertTrue(sizes.observe(1.5f, 50_000_000));
        assertFalse(sizes.observe(1.5f, 1_000_000_000));
        assertFalse(sizes.observe(1, 1_000_000_020));
    }

    @Test
    void alternatingTwoFixedSizesDoNotStartAnAnimation() {
        var sizes = new AnimatedFontSize();
        for (int i = 0; i < 10; i++) assertFalse(sizes.observe(i % 2 == 0 ? 1f : 1.25f, i * 10_000_000L));
        assertTrue(sizes.observe(1.5f, 100_000_000));
    }

    @Test
    void isolatedChangesDoNotCarryAcrossIdlePeriods() {
        var sizes = new AnimatedFontSize();
        assertFalse(sizes.observe(1, 0));
        assertFalse(sizes.observe(2, 1));
        assertFalse(sizes.observe(3, 2_000_000_001L));
    }
}
