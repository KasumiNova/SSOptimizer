package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.Consumer;

/**
 * bridge 缓冲变体 getter 的小缓冲填充辅助：绕过 LWJGL2 {@code BufferChecks}
 * 对 glGet 族缓冲变体的固定下限检查。
 * <p>
 * 动机：LWJGL2 的 {@code glGetInteger/glGetFloat/glGetBoolean(pname, buffer)} 在
 * {@code BufferChecks.checkBuffer} 里<b>无条件要求 remaining ≥ 16</b>（理论最大返回
 * 16 个值，与实际 pname 无关），{@code GL33.glGetSamplerParameter*} 族固定要求 ≥ 4。
 * 调用方按实际语义分配缓冲（ASTD/BoxUtil 用 4 元素缓冲读 GL_VIEWPORT）在单线程
 * 时代直接炸 {@code IllegalArgumentException}；bridge 透传后该崩溃发生在渲染线程
 * 的阻塞通道里，以「渲染线程执行阻塞式调用失败」的形态反复出现（实机数十次）。
 * <p>
 * 规则：
 * <ul>
 *   <li>{@code params.remaining() >= minElements}：原样直通真实调用，零开销；</li>
 *   <li>小于下限：经 ThreadLocal 暂存直接缓冲（16 元素，渲染线程封闭复用，
 *       避免逐次分配）执行真实调用，再把结果拷回调用方缓冲——LWJGL 从 position
 *       开始写且不推进 position，拷回同样写回调用方调用时 position 起点、
 *       保持 position 不变（绝对 put 语义一致）；</li>
 *   <li>暂存缓冲每次调用前先清零：拷贝长度 = min(调用方 remaining, 16)，pname
 *       实际返回值少于拷贝长度时多拷的位是确定的零（而非上一次调用的残留）。</li>
 * </ul>
 * 真实调用以函数接口注入（bridge 调用点传 lambda），单测可用桩替代真实 GL。
 */
final class GetBufferFill {
    /** 暂存缓冲元素数（LWJGL2 glGet 族固定上限 16；按最大元素宽度 8 字节分配）。 */
    private static final int STAGING_ELEMENTS = 16;
    /** 逐线程暂存直接缓冲（实际只在渲染线程使用，ThreadLocal 兜底线程安全）。 */
    private static final ThreadLocal<ByteBuffer> STAGING = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(STAGING_ELEMENTS * 8).order(ByteOrder.nativeOrder()));

    private GetBufferFill() {
    }

    /**
     * glGetInteger 族（固定下限 16）与 glGetSamplerParameter 族（下限 4）的填充入口。
     *
     * @param params       调用方缓冲（写入起点 = 调用时 position，position 保持不变）
     * @param minElements  LWJGL2 对该入口的固定检查下限
     * @param realCall     真实 GL 调用（接收实际执行所用的缓冲）
     */
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

    /** {@link #fillInts} 的 FloatBuffer 版本（glGetFloat 族，固定下限 16）。 */
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

    /** {@link #fillInts} 的 ByteBuffer 版本（glGetBoolean 族，固定下限 16）。 */
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

    /** {@link #fillInts} 的 LongBuffer 版本（glGetInteger64 族，固定下限 16）。 */
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
