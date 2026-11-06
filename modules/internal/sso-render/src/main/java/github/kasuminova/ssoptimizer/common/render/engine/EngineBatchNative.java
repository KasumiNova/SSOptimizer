package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.nio.ByteBuffer;

public final class EngineBatchNative {
    static {
        NativeRuntime.ensureLoaded();
    }

    private EngineBatchNative() {
    }

    /** commandBuffer 必须为 direct/nativeOrder；返回高 32 位顶点偏移、低 32 位索引偏移。 */
    public static native long nativeFlushBatch(ByteBuffer commandBuffer, int commandCount,
                                               int vertexVboId, int vertexCapacity, int vertexWriteOffset,
                                               int indexVboId, int indexCapacity, int indexWriteOffset);
}
