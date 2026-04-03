package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ParallelImagePreloadWorker} 的反射入口选择回归测试。
 */
class ParallelImagePreloadWorkerTest {
    @Test
    void resolvesNamedLoadBytesMethod() throws Exception {
        final Method method = resolveMethod(FakeDeferredLoaderWithNamedLoader.class, "loadBytes", byte[].class, String.class);

        assertEquals("loadBytes", method.getName());
    }

    @Test
    void resolvesNamedDecodeImageMethod() throws Exception {
        final Method method = resolveMethod(FakeDeferredLoaderWithNamedLoader.class, "decodeImage", java.awt.image.BufferedImage.class, String.class);

        assertEquals("decodeImage", method.getName());
    }

    private static Method resolveMethod(final Class<?> owner,
                                        final String methodName,
                                        final Class<?> returnType,
                                        final Class<?>... parameterTypes) throws Exception {
        final Method method = owner.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        assertEquals(returnType, method.getReturnType());
        return method;
    }

    static final class FakeDeferredLoaderWithNamedLoader {
        private static byte[] loadBytes(final String path) {
            return path.getBytes();
        }

        private static java.awt.image.BufferedImage decodeImage(final String path) {
            return new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        }
    }
}