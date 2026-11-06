package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.core.SequenceGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XStreamReferenceIdHelperTest {
    @AfterEach
    void tearDown() {
        XStreamReferenceIdHelper.resetAdaptiveCacheForTests();
    }

    @Test
    void acceptsDefaultSequenceGenerator() {
        assertTrue(XStreamReferenceIdHelper.supportsOptimizedIds(new SequenceGenerator(73)));
    }

    @Test
    void compactIdsMatchIndependentBase36Conversion() {
        final int[] values = {0, 1, 7, 35, 36, 1_000, 100_000, 300_000};
        for (final int value : values) {
            final String expected = Integer.toString(value, 36);
            assertEquals(expected, XStreamReferenceIdHelper.toCompactString(value));
            assertEquals(expected, XStreamReferenceIdHelper.nextReferenceId(value));
        }
    }

    @Test
    void helperRejectsUnsupportedGeneratorTypes() {
        assertFalse(XStreamReferenceIdHelper.supportsOptimizedIds(new Object()));
    }

    @Test
    void helperWarmsAdditionalChunksForLargeReferenceIds() {
        final int initialUpperBound = XStreamReferenceIdHelper.cachedReferenceIdUpperBound();
        final int firstUncachedValue = initialUpperBound + 1;

        assertEquals(
                Integer.toString(firstUncachedValue, 36),
                XStreamReferenceIdHelper.toCompactString(firstUncachedValue)
        );
        assertTrue(XStreamReferenceIdHelper.awaitWarmup(5_000L));
        assertTrue(XStreamReferenceIdHelper.cachedReferenceIdUpperBound() > initialUpperBound);

        final int cachedValue = XStreamReferenceIdHelper.cachedReferenceIdUpperBound();
        assertEquals(
                Integer.toString(cachedValue, 36),
                XStreamReferenceIdHelper.toCompactString(cachedValue)
        );
    }

    @Test
    void explicitWarmupExpandsCacheToRequestedValue() {
        final int requestedValue = XStreamReferenceIdHelper.cachedReferenceIdUpperBound() + 1_000;
        XStreamReferenceIdHelper.requestWarmupToForTests(requestedValue);

        assertTrue(XStreamReferenceIdHelper.awaitWarmup(10_000L));
        assertTrue(XStreamReferenceIdHelper.cachedReferenceIdUpperBound() >= requestedValue);
        assertEquals(
                Integer.toString(requestedValue, 36),
                XStreamReferenceIdHelper.toCompactString(requestedValue)
        );
    }
}
