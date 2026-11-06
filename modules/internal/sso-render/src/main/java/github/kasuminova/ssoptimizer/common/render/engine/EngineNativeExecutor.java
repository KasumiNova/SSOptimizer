package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/** 仅在渲染线程使用；上下文重建回调仅标脏，下一次执行时重建 VBO。 */
final class EngineNativeExecutor {
    static final EngineNativeExecutor INSTANCE = new EngineNativeExecutor();

    private static final int VERTEX_VBO_CAPACITY = 512 * 1024;
    private static final int INDEX_VBO_CAPACITY = 128 * 1024;

    private DynamicVbo vertexVbo;
    private DynamicVbo indexVbo;
    private int nativeVertexWriteOffset;
    private int nativeIndexWriteOffset;
    private volatile boolean contextInvalidated;

    private EngineNativeExecutor() {
    }

    void execute(final ByteBuffer encoded, final int commandCount,
                 final int vertexBytes, final int indexBytes) {
        if (vertexVbo == null || contextInvalidated) {
            recreateVbos();
        }
        if (vertexVbo.ensureCapacity(vertexBytes)) {
            nativeVertexWriteOffset = 0;
        }
        if (indexVbo.ensureCapacity(indexBytes)) {
            nativeIndexWriteOffset = 0;
        }

        final long packed = EngineBatchNative.nativeFlushBatch(encoded, commandCount,
                vertexVbo.getBufferId(), vertexVbo.getCapacityBytes(), nativeVertexWriteOffset,
                indexVbo.getBufferId(), indexVbo.getCapacityBytes(), nativeIndexWriteOffset);
        nativeVertexWriteOffset = (int) (packed >>> 32);
        nativeIndexWriteOffset = (int) packed;
    }

    private void recreateVbos() {
        if (vertexVbo == null) {
            GlDispatch.registerContextRecreatedListener(() -> contextInvalidated = true);
        }
        vertexVbo = new DynamicVbo(GL15.GL_ARRAY_BUFFER, VERTEX_VBO_CAPACITY);
        indexVbo = new DynamicVbo(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_VBO_CAPACITY);
        nativeVertexWriteOffset = 0;
        nativeIndexWriteOffset = 0;
        contextInvalidated = false;
    }
}
