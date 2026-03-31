package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ParallelImagePreloadQueueTrackerTest {
    @AfterEach
    void tearDown() {
        ParallelImagePreloadQueueTracker.clearPending();
    }

    @Test
    void tracksDuplicateImageRequestsWithoutFallingOutOfWaitLoop() {
        List<String> queue = new ArrayList<>();
        Map<String, BufferedImage> results = new ConcurrentHashMap<>();
        BufferedImage sentinel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        BufferedImage actual = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

        ParallelImagePreloadQueueTracker.enqueueImage(queue, "graphics/test.png");
        ParallelImagePreloadQueueTracker.enqueueImage(queue, "graphics/test.png");

        String first = ParallelImagePreloadQueueTracker.dequeueImage(queue, results, sentinel);
        assertEquals("graphics/test.png", first);
        results.put(first, actual);

        BufferedImage awaited = ParallelImagePreloadQueueTracker.awaitImage(results, "graphics/test.png", sentinel);
        assertNotNull(awaited);

        String second = ParallelImagePreloadQueueTracker.dequeueImage(queue, results, sentinel);
        assertEquals("graphics/test.png", second);
        results.remove(second);

        assertNull(ParallelImagePreloadQueueTracker.awaitImage(results, "graphics/test.png", sentinel));
    }

    @Test
    void byteRequestsReturnNullOncePendingAndResultsAreGone() {
        List<String> queue = new ArrayList<>();
        Map<String, byte[]> results = new ConcurrentHashMap<>();
        byte[] sentinel = new byte[0];

        ParallelImagePreloadQueueTracker.enqueueBytes(queue, "data/test.bin");
        String dequeued = ParallelImagePreloadQueueTracker.dequeueBytes(queue, results, sentinel);
        assertEquals("data/test.bin", dequeued);
        results.remove(dequeued);

        assertNull(ParallelImagePreloadQueueTracker.awaitBytes(results, dequeued, sentinel));
    }
}