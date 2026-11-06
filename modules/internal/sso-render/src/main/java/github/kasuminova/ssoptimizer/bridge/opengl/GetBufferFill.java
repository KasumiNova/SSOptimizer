package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.Consumer;

/** 以逐线程暂存缓冲绕过 LWJGL2 的固定容量检查，并保持调用方缓冲的 position 不变。 */
final class GetBufferFill {
    private static final int STAGING_ELEMENTS = 16;
    private static final ThreadLocal<ByteBuffer> STAGING = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(STAGING_ELEMENTS * 8).order(ByteOrder.nativeOrder()));

    private GetBufferFill() {
    }

    static void fillInts(final IntBuffer params, final int minElements,
                         final Consumer<IntBuffer> realCall) {
        if (params.remaining() >= minElements) {
            realCall.accept(params);
            return;
        }
        final IntBuffer staging = STAGING.get().asIntBuffer();
        staging.clear();
        for (int i = 0; i < STAGING_ELEMENTS; i++) {
            staging.put(i, 0);
        }
        realCall.accept(staging);
        final int count = Math.min(params.remaining(), STAGING_ELEMENTS);
        final int base = params.position();
        for (int i = 0; i < count; i++) {
            params.put(base + i, staging.get(i));
        }
    }

    static void fillFloats(final FloatBuffer params, final int minElements,
                           final Consumer<FloatBuffer> realCall) {
        if (params.remaining() >= minElements) {
            realCall.accept(params);
            return;
        }
        final FloatBuffer staging = STAGING.get().asFloatBuffer();
        staging.clear();
        for (int i = 0; i < STAGING_ELEMENTS; i++) {
            staging.put(i, 0.0f);
        }
        realCall.accept(staging);
        final int count = Math.min(params.remaining(), STAGING_ELEMENTS);
        final int base = params.position();
        for (int i = 0; i < count; i++) {
            params.put(base + i, staging.get(i));
        }
    }

    static void fillBooleans(final ByteBuffer params, final int minElements,
                             final Consumer<ByteBuffer> realCall) {
        if (params.remaining() >= minElements) {
            realCall.accept(params);
            return;
        }
        final ByteBuffer staging = STAGING.get();
        staging.clear();
        for (int i = 0; i < STAGING_ELEMENTS; i++) {
            staging.put(i, (byte) 0);
        }
        staging.limit(STAGING_ELEMENTS);
        realCall.accept(staging);
        final int count = Math.min(params.remaining(), STAGING_ELEMENTS);
        final int base = params.position();
        for (int i = 0; i < count; i++) {
            params.put(base + i, staging.get(i));
        }
    }

    static void fillDoubles(final java.nio.DoubleBuffer params, final int minElements,
                            final Consumer<java.nio.DoubleBuffer> realCall) {
        if (params.remaining() >= minElements) {
            realCall.accept(params);
            return;
        }
        final java.nio.DoubleBuffer staging = STAGING.get().asDoubleBuffer();
        staging.clear();
        for (int i = 0; i < STAGING_ELEMENTS; i++) {
            staging.put(i, 0.0);
        }
        realCall.accept(staging);
        final int count = Math.min(params.remaining(), STAGING_ELEMENTS);
        final int base = params.position();
        for (int i = 0; i < count; i++) {
            params.put(base + i, staging.get(i));
        }
    }

    static void fillLongs(final java.nio.LongBuffer params, final int minElements,
                          final Consumer<java.nio.LongBuffer> realCall) {
        if (params.remaining() >= minElements) {
            realCall.accept(params);
            return;
        }
        final java.nio.LongBuffer staging = STAGING.get().asLongBuffer();
        staging.clear();
        for (int i = 0; i < STAGING_ELEMENTS; i++) {
            staging.put(i, 0L);
        }
        realCall.accept(staging);
        final int count = Math.min(params.remaining(), STAGING_ELEMENTS);
        final int base = params.position();
        for (int i = 0; i < count; i++) {
            params.put(base + i, staging.get(i));
        }
    }
}
