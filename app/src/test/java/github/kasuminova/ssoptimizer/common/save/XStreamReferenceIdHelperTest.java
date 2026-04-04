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
    void readsDefaultSequenceGeneratorCounter() {
        final SequenceGenerator generator = new SequenceGenerator(73);

        assertTrue(XStreamReferenceIdHelper.supportsOptimizedIds(generator));
        assertEquals(73, XStreamReferenceIdHelper.readSequenceCounter(generator));
    }

    @Test
    void compactIdsUseBase36Strings() {
        assertEquals("0", XStreamReferenceIdHelper.toCompactString(0));
        assertEquals("z", XStreamReferenceIdHelper.toCompactString(35));
        assertEquals("10", XStreamReferenceIdHelper.toCompactString(36));
        assertEquals("11", XStreamReferenceIdHelper.toCompactString(37));
        assertEquals("100", XStreamReferenceIdHelper.toCompactString(36 * 36));
        assertEquals("21", XStreamReferenceIdHelper.nextReferenceId(73));
        assertEquals(XStreamReferenceIdHelper.toCompactStringUncached(4096), XStreamReferenceIdHelper.toCompactString(4096));
        assertEquals(XStreamReferenceIdHelper.toCompactStringUncached(65_535), XStreamReferenceIdHelper.toCompactString(65_535));
        assertEquals(XStreamReferenceIdHelper.toCompactStringUncached(262_144), XStreamReferenceIdHelper.toCompactString(262_144));
    }

    @Test
    void helperRejectsUnsupportedGeneratorTypes() {
        assertFalse(XStreamReferenceIdHelper.supportsOptimizedIds(new Object()));
    }

    @Test
    void helperWarmsAdditionalChunksForLargeReferenceIds() {
        assertEquals(65_535, XStreamReferenceIdHelper.cachedReferenceIdUpperBound());

        assertEquals(XStreamReferenceIdHelper.toCompactStringUncached(262_144), XStreamReferenceIdHelper.toCompactString(262_144));
        assertTrue(XStreamReferenceIdHelper.awaitWarmup(5_000L));
        assertTrue(XStreamReferenceIdHelper.cachedReferenceIdUpperBound() >= 262_143);
        assertEquals(XStreamReferenceIdHelper.toCompactStringUncached(131_072), XStreamReferenceIdHelper.toCompactString(131_072));
    }

    @Test
    void explicitWarmupCanExpandCacheUpToConfiguredLimit() {
        XStreamReferenceIdHelper.requestWarmupToForTests(700_000);

        assertTrue(XStreamReferenceIdHelper.awaitWarmup(10_000L));
        assertTrue(XStreamReferenceIdHelper.cachedReferenceIdUpperBound() >= 720_895);
    }
}