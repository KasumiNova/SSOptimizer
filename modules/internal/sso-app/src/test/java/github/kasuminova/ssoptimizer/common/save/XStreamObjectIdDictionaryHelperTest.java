package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XStreamObjectIdDictionaryHelperTest {
    @Test
    void lookupContainsAndRemoveUseReusableProbe() {
        Object key = new Object();
        Map<Object, Object> map = new HashMap<>();
        map.put(new WrapperLike(key), "ref-1");

        assertEquals("ref-1", XStreamObjectIdDictionaryHelper.lookupId(map, key));
        assertTrue(XStreamObjectIdDictionaryHelper.containsId(map, key));

        XStreamObjectIdDictionaryHelper.removeId(map, key);

        assertFalse(XStreamObjectIdDictionaryHelper.containsId(map, key));
        assertNull(XStreamObjectIdDictionaryHelper.lookupId(map, key));
    }

    @Test
    void probeTreatsClearedWeakWrappersAsMissing() {
        Object key = new Object();
        Map<Object, Object> map = new HashMap<>();
        map.put(new ClearedWrapperLike(key), "ref-1");

        assertNull(XStreamObjectIdDictionaryHelper.lookupId(map, key));
        assertFalse(XStreamObjectIdDictionaryHelper.containsId(map, key));
    }

    @Test
    void explicitReusableProbeCanBeReusedAcrossOperations() {
        Object firstKey = new Object();
        Object secondKey = new Object();
        Map<Object, Object> map = new HashMap<>();
        map.put(new WrapperLike(firstKey), "ref-1");
        map.put(new WrapperLike(secondKey), "ref-2");

        XStreamObjectIdDictionaryHelper.ReusableIdProbe probe = XStreamObjectIdDictionaryHelper.createReusableProbe();

        assertEquals("ref-1", XStreamObjectIdDictionaryHelper.lookupId(map, firstKey, probe));
        assertTrue(XStreamObjectIdDictionaryHelper.containsId(map, secondKey, probe));

        XStreamObjectIdDictionaryHelper.removeId(map, firstKey, probe);

        assertNull(XStreamObjectIdDictionaryHelper.lookupId(map, firstKey, probe));
        assertEquals("ref-2", XStreamObjectIdDictionaryHelper.lookupId(map, secondKey, probe));
    }

    private static final class WrapperLike extends WeakReference<Object> {
        private final int hashCode;

        private WrapperLike(final Object target) {
            super(target);
            this.hashCode = System.identityHashCode(target);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof WrapperLike wrapper && wrapper.get() == get();
        }
    }

    private static final class ClearedWrapperLike extends WeakReference<Object> {
        private final int hashCode;

        private ClearedWrapperLike(final Object target) {
            super(target);
            clear();
            this.hashCode = System.identityHashCode(target);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof ClearedWrapperLike wrapper && wrapper.get() == get();
        }
    }
}
