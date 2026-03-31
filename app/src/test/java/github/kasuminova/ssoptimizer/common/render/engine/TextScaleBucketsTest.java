package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextScaleBucketsTest {
    @Test
    void bucketsScalesToStableEighthSteps() {
        assertEquals(1.0f, TextScaleBuckets.bucketScale(1.02f));
        assertEquals(1.125f, TextScaleBuckets.bucketScale(1.08f));
        assertEquals(1.25f, TextScaleBuckets.bucketScale(1.24f));
        assertEquals(0.5f, TextScaleBuckets.bucketScale(0.2f));
        assertEquals(4.0f, TextScaleBuckets.bucketScale(8.0f));
    }

    @Test
    void treatsFiniteNearOneScaleAsIdentity() {
        assertTrue(TextScaleBuckets.isIdentityScale(1.01f));
        assertFalse(TextScaleBuckets.isIdentityScale(1.18f));
        assertTrue(TextScaleBuckets.isIdentityScale(Float.NaN));
    }
}
