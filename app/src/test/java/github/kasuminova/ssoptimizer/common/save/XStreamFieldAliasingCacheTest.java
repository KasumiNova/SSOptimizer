package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class XStreamFieldAliasingCacheTest {
    @Test
    void cachesSerializedMemberResults() {
        XStreamFieldAliasingCache cache = new XStreamFieldAliasingCache();
        AtomicInteger calls = new AtomicInteger();

        String first = cache.getOrResolveSerializedMember(Sample.class, "value", () -> {
            calls.incrementAndGet();
            return "alias";
        });
        String second = cache.getOrResolveSerializedMember(Sample.class, "value", () -> {
            calls.incrementAndGet();
            return "other";
        });

        assertEquals("alias", first);
        assertEquals("alias", second);
        assertEquals(1, calls.get());
    }

    @Test
    void cachesRealMemberResultsSeparately() {
        XStreamFieldAliasingCache cache = new XStreamFieldAliasingCache();
        AtomicInteger serializedCalls = new AtomicInteger();
        AtomicInteger realCalls = new AtomicInteger();

        cache.getOrResolveSerializedMember(Sample.class, "value", () -> {
            serializedCalls.incrementAndGet();
            return "alias";
        });
        String real = cache.getOrResolveRealMember(Sample.class, "alias", () -> {
            realCalls.incrementAndGet();
            return "value";
        });

        assertEquals("value", real);
        assertEquals(1, serializedCalls.get());
        assertEquals(1, realCalls.get());
    }

    @Test
    void clearDropsCachedMembers() {
        XStreamFieldAliasingCache cache = new XStreamFieldAliasingCache();
        AtomicInteger calls = new AtomicInteger();

        cache.getOrResolveSerializedMember(Sample.class, "value", () -> {
            calls.incrementAndGet();
            return "alias";
        });
        cache.clear();
        cache.getOrResolveSerializedMember(Sample.class, "value", () -> {
            calls.incrementAndGet();
            return "alias2";
        });

        assertEquals(2, calls.get());
    }

    @Test
    void cachesMissingMembers() {
        XStreamFieldAliasingCache cache = new XStreamFieldAliasingCache();
        AtomicInteger calls = new AtomicInteger();

        String first = cache.getOrResolveRealMember(Sample.class, "missing", () -> {
            calls.incrementAndGet();
            return null;
        });
        String second = cache.getOrResolveRealMember(Sample.class, "missing", () -> {
            calls.incrementAndGet();
            return "value";
        });

        assertNull(first);
        assertNull(second);
        assertEquals(1, calls.get());
    }

    private static final class Sample {
        @SuppressWarnings("unused")
        private String value;
    }
}
