package github.kasuminova.ssoptimizer.common.loading;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Tracks pending deferred image/byte preload requests using per-path latches
 * so callers block without polling while preserving the base game's
 * queue/result-map protocol.
 */
public final class ParallelImagePreloadQueueTracker {
    private static final Map<String, CountDownLatch> PENDING_IMAGE_LATCHES = new ConcurrentHashMap<>();
    private static final Map<String, CountDownLatch> PENDING_BYTE_LATCHES  = new ConcurrentHashMap<>();

    private ParallelImagePreloadQueueTracker() {
    }

    public static void enqueueImage(final List<String> queue, final String path) {
        if (queue == null || path == null) {
            return;
        }

        synchronized (queue) {
            PENDING_IMAGE_LATCHES.putIfAbsent(path, new CountDownLatch(1));
            queue.add(path);
        }
    }

    public static void enqueueBytes(final List<String> queue, final String path) {
        if (queue == null || path == null) {
            return;
        }

        synchronized (queue) {
            PENDING_BYTE_LATCHES.putIfAbsent(path, new CountDownLatch(1));
            queue.add(path);
        }
    }

    public static BufferedImage awaitImage(final Map<String, BufferedImage> resultMap,
                                           final String path,
                                           final BufferedImage sentinel) {
        if (resultMap == null || path == null) {
            return null;
        }

        final CountDownLatch latch = PENDING_IMAGE_LATCHES.get(path);
        if (latch != null) {
            try {
                latch.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        final BufferedImage image = resultMap.get(path);
        if (image != null && image != sentinel) {
            resultMap.remove(path);
            return image;
        }
        return null;
    }

    public static byte[] awaitBytes(final Map<String, byte[]> resultMap,
                                    final String path,
                                    final byte[] sentinel) {
        if (resultMap == null || path == null) {
            return null;
        }

        final CountDownLatch latch = PENDING_BYTE_LATCHES.get(path);
        if (latch != null) {
            try {
                latch.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        final byte[] bytes = resultMap.get(path);
        if (bytes != null && bytes != sentinel) {
            resultMap.remove(path);
            return bytes;
        }
        return null;
    }

    /**
     * worker 完成（或跳过）某路径的加载后唤醒等待方。无论结果是否写入
     * resultMap 都必须调用，否则 {@link #awaitImage}/{@link #awaitBytes} 会一直阻塞。
     */
    public static void completeImage(final String path) {
        final CountDownLatch latch = PENDING_IMAGE_LATCHES.remove(path);
        if (latch != null) {
            latch.countDown();
        }
    }

    /** 字节队列版本的 {@link #completeImage}。 */
    public static void completeBytes(final String path) {
        final CountDownLatch latch = PENDING_BYTE_LATCHES.remove(path);
        if (latch != null) {
            latch.countDown();
        }
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
            return path;
        }
    }

    public static void clearPending() {
        for (final CountDownLatch latch : PENDING_IMAGE_LATCHES.values()) {
            latch.countDown();
        }
        for (final CountDownLatch latch : PENDING_BYTE_LATCHES.values()) {
            latch.countDown();
        }
        PENDING_IMAGE_LATCHES.clear();
        PENDING_BYTE_LATCHES.clear();
    }
}
