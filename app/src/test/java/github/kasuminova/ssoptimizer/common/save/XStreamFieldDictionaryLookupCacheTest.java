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

    @Test
    void separatesQualifiedLookupsByDeclaringType() throws Exception {
        XStreamFieldDictionaryLookupCache cache = new XStreamFieldDictionaryLookupCache();
        AtomicInteger calls = new AtomicInteger();
        Field baseField = Parent.class.getDeclaredField("value");
        Field childField = Child.class.getDeclaredField("value");

        Field first = cache.getOrResolve(Child.class, "value", Parent.class, () -> {
            calls.incrementAndGet();
            return baseField;
        });
        Field second = cache.getOrResolve(Child.class, "value", Child.class, () -> {
            calls.incrementAndGet();
            return childField;
        });
        Field firstAgain = cache.getOrResolve(Child.class, "value", Parent.class, () -> {
            calls.incrementAndGet();
            return childField;
        });

        assertSame(baseField, first);
        assertSame(childField, second);
        assertSame(baseField, firstAgain);
        assertEquals(2, calls.get());
    }

    @Test
    void clearDropsAllNestedCaches() throws Exception {
        XStreamFieldDictionaryLookupCache cache = new XStreamFieldDictionaryLookupCache();
        AtomicInteger calls = new AtomicInteger();
        Field field = Sample.class.getDeclaredField("value");

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
        private int value;
    }

    private static class Parent {
        private int value;
    }

    private static final class Child extends Parent {
        private int value;
    }
}