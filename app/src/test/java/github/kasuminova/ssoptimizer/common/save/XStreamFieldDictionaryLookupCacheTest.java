package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class XStreamFieldDictionaryLookupCacheTest {
    @Test
    void cachesResolvedFields() throws Exception {
        XStreamFieldDictionaryLookupCache cache = new XStreamFieldDictionaryLookupCache();
        AtomicInteger calls = new AtomicInteger();
        Field field = Sample.class.getDeclaredField("value");

        Field first = cache.getOrResolve(Sample.class, "value", null, () -> {
            calls.incrementAndGet();
            return field;
        });
        Field second = cache.getOrResolve(Sample.class, "value", null, () -> {
            calls.incrementAndGet();
            return null;
        });

        assertSame(field, first);
        assertSame(field, second);
        assertEquals(1, calls.get());
    }

    @Test
    void cachesMissingFields() {
        XStreamFieldDictionaryLookupCache cache = new XStreamFieldDictionaryLookupCache();
        AtomicInteger calls = new AtomicInteger();

        Field first = cache.getOrResolve(Sample.class, "missing", null, () -> {
            calls.incrementAndGet();
            return null;
        });
        Field second = cache.getOrResolve(Sample.class, "missing", null, () -> {
            calls.incrementAndGet();
            return Sample.class.getDeclaredFields()[0];
        });

        assertNull(first);
        assertNull(second);
        assertEquals(1, calls.get());
    }

    private static final class Sample {
        private int value;
    }
}