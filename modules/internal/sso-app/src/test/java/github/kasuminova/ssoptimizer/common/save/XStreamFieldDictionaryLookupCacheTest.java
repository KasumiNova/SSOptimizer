package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class XStreamFieldDictionaryLookupCacheTest {
    @Test
    void cachesResolvedFields() {
        final XStreamFieldDictionaryLookupCache<Object> cache = new XStreamFieldDictionaryLookupCache<>();
        final AtomicInteger calls = new AtomicInteger();
        final Object field = new Object();

        final Object first = cache.getOrResolve(Sample.class, "value", null, () -> {
            calls.incrementAndGet();
            return field;
        });
        final Object second = cache.getOrResolve(Sample.class, "value", null, () -> {
            calls.incrementAndGet();
            return null;
        });

        assertSame(field, first);
        assertSame(field, second);
        assertEquals(1, calls.get());
    }

    @Test
    void cachesMissingFields() {
        final XStreamFieldDictionaryLookupCache<Object> cache = new XStreamFieldDictionaryLookupCache<>();
        final AtomicInteger calls = new AtomicInteger();

        final Object first = cache.getOrResolve(Sample.class, "missing", null, () -> {
            calls.incrementAndGet();
            return null;
        });
        final Object second = cache.getOrResolve(Sample.class, "missing", null, () -> {
            calls.incrementAndGet();
            return new Object();
        });

        assertNull(first);
        assertNull(second);
        assertEquals(1, calls.get());
    }

    @Test
    void separatesQualifiedLookupsByDeclaringType() {
        final XStreamFieldDictionaryLookupCache<Object> cache = new XStreamFieldDictionaryLookupCache<>();
        final AtomicInteger calls = new AtomicInteger();
        final Object baseField = new Object();
        final Object childField = new Object();

        final Object first = cache.getOrResolve(Child.class, "value", Parent.class, () -> {
            calls.incrementAndGet();
            return baseField;
        });
        final Object second = cache.getOrResolve(Child.class, "value", Child.class, () -> {
            calls.incrementAndGet();
            return childField;
        });
        final Object firstAgain = cache.getOrResolve(Child.class, "value", Parent.class, () -> {
            calls.incrementAndGet();
            return childField;
        });

        assertSame(baseField, first);
        assertSame(childField, second);
        assertSame(baseField, firstAgain);
        assertEquals(2, calls.get());
    }

    @Test
    void clearDropsAllNestedCaches() {
        final XStreamFieldDictionaryLookupCache<Object> cache = new XStreamFieldDictionaryLookupCache<>();
        final AtomicInteger calls = new AtomicInteger();
        final Object field = new Object();

        cache.getOrResolve(Sample.class, "value", null, () -> {
            calls.incrementAndGet();
            return field;
        });
        cache.clear();
        cache.getOrResolve(Sample.class, "value", null, () -> {
            calls.incrementAndGet();
            return field;
        });

        assertEquals(2, calls.get());
    }

    private static final class Sample {
    }

    private static class Parent {
    }

    private static final class Child extends Parent {
    }
}
