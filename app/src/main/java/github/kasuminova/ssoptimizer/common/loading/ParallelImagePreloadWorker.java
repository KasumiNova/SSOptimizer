package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Replacement deferred preload worker that keeps the base-game queue/result-map
 * contract intact while pairing it with {@link ParallelImagePreloadQueueTracker}
 * to avoid repeated synchronized list contains scans on the main thread.
 */
public final class ParallelImagePreloadWorker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ParallelImagePreloadWorker.class);

    private static volatile LoaderAccess cachedAccess;

    private final LoaderAccess access;

    public ParallelImagePreloadWorker() {
        this.access = loaderAccess();
    }

    private static LoaderAccess loaderAccess() {
        LoaderAccess access = cachedAccess;
        if (access != null) {
            return access;
        }

        synchronized (ParallelImagePreloadWorker.class) {
            if (cachedAccess != null) {
                return cachedAccess;
            }

            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Class<?> deferredLoaderClass = Class.forName("com.fs.graphics.L", true, loader);

                cachedAccess = new LoaderAccess(
                        field(deferredLoaderClass, "class"),
                        field(deferredLoaderClass, "Ø00000"),
                        field(deferredLoaderClass, "õ00000"),
                        field(deferredLoaderClass, "new"),
                        staticFieldValue(deferredLoaderClass, "Ô00000"),
                        staticFieldValue(deferredLoaderClass, "Ö00000"),
                        method(deferredLoaderClass, "o00000", BufferedImage.class, String.class),
                        method(deferredLoaderClass, "Ô00000", byte[].class, String.class)
                );
                return cachedAccess;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to access deferred preload worker internals", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T staticFieldValue(final Class<?> owner, final String fieldName) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(final Class<?> owner, final String fieldName) throws ReflectiveOperationException {
        return staticFieldValue(owner, fieldName);
    }

    private static Method method(final Class<?> owner,
                                 final String methodName,
                                 final Class<?> returnType,
                                 final Class<?>... parameterTypes) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        if (!returnType.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException(methodName + " return type mismatch");
        }
        return method;
    }

    private static boolean isInterruptedFailure(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            if (current instanceof InvocationTargetException invocationTargetException) {
                current = invocationTargetException.getTargetException();
                continue;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public void run() {
        processByteQueue();
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        processImageQueue();
    }

    private void processByteQueue() {
        while (true) {
            String path = ParallelImagePreloadQueueTracker.dequeueBytes(
                    access.byteQueue(),
                    access.byteResults(),
                    access.byteSentinel()
            );
            if (path == null) {
                return;
            }

            try {
                access.byteResults().put(path, access.loadBytes(path));
            } catch (Throwable throwable) {
                access.byteResults().remove(path);
                if (isInterruptedFailure(throwable)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                LOGGER.error(throwable.getMessage(), throwable);
            }

            if (Thread.interrupted()) {
                return;
            }
        }
    }

    private void processImageQueue() {
        while (true) {
            String path = ParallelImagePreloadQueueTracker.dequeueImage(
                    access.imageQueue(),
                    access.imageResults(),
                    access.imageSentinel()
            );
            if (path == null) {
                return;
            }

            try {
                access.imageResults().put(path, access.loadImage(path));
            } catch (Throwable throwable) {
                access.imageResults().remove(path);
                if (isInterruptedFailure(throwable)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                LOGGER.error(throwable.getMessage(), throwable);
            }

            if (Thread.interrupted()) {
                return;
            }
        }
    }

    private record LoaderAccess(List<String> imageQueue,
                                Map<String, BufferedImage> imageResults,
                                List<String> byteQueue,
                                Map<String, byte[]> byteResults,
                                BufferedImage imageSentinel,
                                byte[] byteSentinel,
                                Method loadImageMethod,
                                Method loadBytesMethod) {
        private BufferedImage loadImage(final String path) throws ReflectiveOperationException {
            return (BufferedImage) loadImageMethod.invoke(null, path);
        }

        private byte[] loadBytes(final String path) throws ReflectiveOperationException {
            return (byte[]) loadBytesMethod.invoke(null, path);
        }
    }
}