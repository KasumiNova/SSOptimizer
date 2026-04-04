package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.core.SequenceGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XStreamReferenceIdHelperTest {
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
    }

    @Test
    void helperRejectsUnsupportedGeneratorTypes() {
        assertFalse(XStreamReferenceIdHelper.supportsOptimizedIds(new Object()));
    }
}