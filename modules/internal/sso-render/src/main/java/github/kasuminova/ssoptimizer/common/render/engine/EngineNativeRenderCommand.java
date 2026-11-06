package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.bridge.opengl.NativeRenderCommandBase;

import java.nio.ByteBuffer;

/** 主线程编码、渲染线程执行；display list 编译窗口内不得入队。 */
public final class EngineNativeRenderCommand extends NativeRenderCommandBase {
    private final int commandCount;
    private final int vertexBytes;
    private final int indexBytes;

    public EngineNativeRenderCommand(final ByteBuffer encoded,
                                     final int commandCount,
                                     final int vertexBytes,
                                     final int indexBytes) {
        super(encoded);
        this.commandCount = commandCount;
        this.vertexBytes = vertexBytes;
        this.indexBytes = indexBytes;
    }

    @Override
    protected void executeNative(final ByteBuffer encoded) {
        EngineNativeExecutor.INSTANCE.execute(encoded, commandCount, vertexBytes, indexBytes);
    }

    final ByteBuffer encodedBufferForTest() {
        return encodedBuffer();
    }

    public static EngineNativeRenderCommand of(final EngineInstanceCollector.CollectedBatch batch) {
        final int requiredBytes = EngineInstanceCollector.flattenedBytes(batch);
        // C++ 结构体布局要求使用 nativeOrder。
        final ByteBuffer encoded = GlDispatch.snapshotPool().borrow(requiredBytes)
                .order(java.nio.ByteOrder.nativeOrder());
        final int commandCount = EngineInstanceCollector.flatten(batch, encoded);
        return new EngineNativeRenderCommand(encoded, commandCount,
                EngineInstanceCollector.expandedVertexBytes(batch),
                EngineInstanceCollector.expandedIndexBytes(batch));
    }
}
