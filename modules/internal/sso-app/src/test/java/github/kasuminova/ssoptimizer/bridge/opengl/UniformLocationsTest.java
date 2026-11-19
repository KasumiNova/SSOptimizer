package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(10)
class UniformLocationsTest {
    private final UniformLocations locations = new UniformLocations();

    @Test
    void firstLookupUsesDriverAndLinkedProgramCachesMissingAndPresentNames() {
        locations.created(1);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(-1, locations.resolve(1, "unused", () -> missing(calls)));
        assertEquals(-1, locations.resolve(1, "unused", () -> missing(calls)));
        assertEquals(2, calls.get());

        link(1, true);
        assertEquals(-1, locations.resolve(1, "unused", () -> missing(calls)));
        assertEquals(-1, locations.resolve(1, "unused", UniformLocationsTest::unexpectedLookup));
        assertEquals(12, locations.resolve(1, "color", () -> 12));
        assertEquals(12, locations.resolve(1, "color", UniformLocationsTest::unexpectedLookup));
        assertEquals(3, calls.get());
    }

    @Test
    void failedLinksAndInvalidNamesDoNotSuppressDriverLookups() {
        locations.createdLinked(1);
        assertEquals(5, locations.resolve(1, "color", () -> 5));
        link(1, false);
        UniformLocations.LinkToken invalid = locations.recordLink(0);
        locations.beginLink(invalid);
        assertFalse(invalid.knownProgram());
        locations.finishLink(invalid, false);

        AtomicInteger calls = new AtomicInteger();
        for (int program : new int[]{0, 1, 2}) {
            assertEquals(-1, locations.resolve(program, "color", () -> missing(calls)));
            assertEquals(-1, locations.resolve(program, "color", () -> missing(calls)));
        }
        assertEquals(6, calls.get());
    }

    @Test
    void oldLookupCannotPublishAfterDeletionAndNameReuse() throws Exception {
        locations.createdLinked(1);
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CompletableFuture<Integer> oldResult = new CompletableFuture<>();
        CompletableFuture<Integer> lookup = CompletableFuture.supplyAsync(() -> locations.resolve(1, "color", () -> {
            lookupStarted.countDown();
            return oldResult.join();
        }));
        try {
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS));
            locations.invalidate(1);
            locations.createdLinked(1);
        } finally {
            oldResult.complete(5);
        }

        assertEquals(5, lookup.get(5, TimeUnit.SECONDS));
        assertEquals(16, locations.resolve(1, "color", () -> 16));
        assertEquals(16, locations.resolve(1, "color", UniformLocationsTest::unexpectedLookup));
    }

    @Test
    void linkingADeletedButStillCurrentProgramDoesNotRestoreCaching() {
        locations.createdLinked(1);
        locations.invalidate(1);
        link(1, true);

        assertEquals(5, locations.resolve(1, "color", () -> 5));
        assertEquals(-1, locations.resolve(1, "color", () -> -1));
        assertEquals(-1, locations.resolve(1, "color", () -> -1));
    }

    @Test
    void lookupStartedWhileLinkPendingCannotPublishToTheCompletedLink() {
        locations.created(1);
        UniformLocations.LinkToken pending = locations.recordLink(1);
        assertEquals(-1, locations.resolve(1, "color", () -> {
            locations.beginLink(pending);
            locations.finishLink(pending, true);
            return -1;
        }));
        assertEquals(16, locations.resolve(1, "color", () -> 16));
        assertEquals(16, locations.resolve(1, "color", UniformLocationsTest::unexpectedLookup));
    }

    @Test
    void invertedLinkExecutionOrderKeepsLookupsUncachedUntilAnUnambiguousLink() {
        locations.createdLinked(1);
        assertEquals(5, locations.resolve(1, "color", () -> 5));
        UniformLocations.LinkToken first = locations.recordLink(1);
        UniformLocations.LinkToken second = locations.recordLink(1);
        locations.beginLink(second);
        locations.finishLink(second, true);
        assertEquals(6, locations.resolve(1, "color", () -> 6));
        assertEquals(6, locations.resolve(1, "color", () -> 6));
        locations.beginLink(first);
        locations.finishLink(first, true);
        assertEquals(7, locations.resolve(1, "color", () -> 7));
        assertEquals(8, locations.resolve(1, "color", () -> 8));

        link(1, true);
        assertEquals(17, locations.resolve(1, "color", () -> 17));
        assertEquals(17, locations.resolve(1, "color", UniformLocationsTest::unexpectedLookup));
    }

    @Test
    void contextResetDropsCachedValuesAndLateLinkDisablesNewGeneration() {
        locations.createdLinked(1);
        assertEquals(5, locations.resolve(1, "color", () -> 5));
        UniformLocations.LinkToken old = locations.recordLink(1);
        locations.clear();
        locations.createdLinked(1);
        assertEquals(10, locations.resolve(1, "color", () -> 10));

        locations.beginLink(old);
        assertEquals(11, locations.resolve(1, "color", () -> 11));
        locations.finishLink(old, true);
        assertEquals(12, locations.resolve(1, "color", () -> 12));
        assertEquals(13, locations.resolve(1, "color", () -> 13));

        link(1, true);
        assertEquals(17, locations.resolve(1, "color", () -> 17));
        assertEquals(17, locations.resolve(1, "color", UniformLocationsTest::unexpectedLookup));
    }

    @Test
    void abandonedLinkReleasesPendingStateWithoutPublishingItsResult() {
        locations.createdLinked(1);
        UniformLocations.LinkToken abandoned = locations.recordLink(1);
        locations.finishLink(abandoned, false);
        assertEquals(6, locations.resolve(1, "color", () -> 6));
        assertEquals(7, locations.resolve(1, "color", () -> 7));

        link(1, true);
        assertEquals(8, locations.resolve(1, "color", () -> 8));
        assertEquals(8, locations.resolve(1, "color", UniformLocationsTest::unexpectedLookup));
    }

    private void link(int program, boolean linked) {
        UniformLocations.LinkToken token = locations.recordLink(program);
        locations.beginLink(token);
        locations.finishLink(token, linked);
    }

    private static int missing(AtomicInteger calls) {
        calls.incrementAndGet();
        return -1;
    }

    private static int unexpectedLookup() {
        return fail("已建立的缓存应返回同代的查询结果");
    }
}
