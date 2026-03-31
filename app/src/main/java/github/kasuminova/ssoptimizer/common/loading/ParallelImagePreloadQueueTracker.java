package github.kasuminova.ssoptimizer.common.loading;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending deferred image/byte preload requests using concurrent path
 * counters so callers can avoid O(n) synchronized list scans while preserving
 * the base game's queue/result-map protocol.
 */
public final class ParallelImagePreloadQueueTracker {
    private static final Map<String, Integer> PENDING_IMAGE_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> PENDING_BYTE_COUNTS  = new ConcurrentHashMap<>();

    private ParallelImagePreloadQueueTracker() {
    }

    public static void enqueueImage(final List<String> queue, final String path) {
        if (queue == null || path == null) {
            return;
        }

        synchronized (queue) {
            increment(PENDING_IMAGE_COUNTS, path);
            queue.add(path);
        }
    }

    public static void enqueueBytes(final List<String> queue, final String path) {
        if (queue == null || path == null) {
            return;
        }

        synchronized (queue) {
            increment(PENDING_BYTE_COUNTS, path);
            queue.add(path);
        }
    }

    public static BufferedImage awaitImage(final Map<String, BufferedImage> resultMap,
                                           final String path,
                                           final BufferedImage sentinel) {
        if (resultMap == null || path == null) {
            return null;
        }

        while (isPending(PENDING_IMAGE_COUNTS, path) || resultMap.containsKey(path)) {
            BufferedImage image = resultMap.get(path);
            if (image != null && image != sentinel) {
                resultMap.remove(path);
                return image;
            }

            try {
                Thread.sleep(10L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    public static byte[] awaitBytes(final Map<String, byte[]> resultMap,
                                    final String path,
                                    final byte[] sentinel) {
        if (resultMap == null || path == null) {
            return null;
        }

        while (isPending(PENDING_BYTE_COUNTS, path) || resultMap.containsKey(path)) {
            byte[] bytes = resultMap.get(path);
            if (bytes != null && bytes != sentinel) {
                resultMap.remove(path);
                return bytes;
            }

            try {
                Thread.sleep(10L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    static String dequeueImage(final List<String> queue,
                               final Map<String, BufferedImage> resultMap,
                               final BufferedImage sentinel) {
        if (queue == null || resultMap == null) {
            return null;
        }

        synchronized (queue) {
            if (queue.isEmpty()) {
                return null;
            }

            String path = queue.remove(0);
            resultMap.put(path, sentinel);
            decrement(PENDING_IMAGE_COUNTS, path);
            return path;
        }
    }

    static String dequeueBytes(final List<String> queue,
                               final Map<String, byte[]> resultMap,
                               final byte[] sentinel) {
        if (queue == null || resultMap == null) {
            return null;
        }

        synchronized (queue) {
            if (queue.isEmpty()) {
                return null;
            }

            String path = queue.remove(0);
            resultMap.put(path, sentinel);
            decrement(PENDING_BYTE_COUNTS, path);
            return path;
        }
    }

    public static void clearPending() {
        PENDING_IMAGE_COUNTS.clear();
        PENDING_BYTE_COUNTS.clear();
    }

    private static boolean isPending(final Map<String, Integer> pendingCounts, final String path) {
        return pendingCounts.containsKey(path);
    }

    private static void increment(final Map<String, Integer> pendingCounts, final String path) {
        pendingCounts.merge(path, 1, Integer::sum);
    }

    private static void decrement(final Map<String, Integer> pendingCounts, final String path) {
        pendingCounts.computeIfPresent(path, (ignored, count) -> count > 1 ? count - 1 : null);
    }
}