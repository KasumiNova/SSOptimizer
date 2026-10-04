package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.bridge.opengl.NativeRenderCommandBase;

import java.nio.ByteBuffer;

/**
 * 引擎渲染的 native 队列命令：录制侧（主线程）把一艘船全部引擎槽的
 * 条带/核心/辉光实例扁平化进池化缓冲（{@link EngineInstanceCollector#flatten}），
 * 渲染线程执行侧经 {@link EngineBatchNative#nativeFlushBatch} 单次 JNI 完成
 * 顶点展开、环形 VBO 写入与逐组绘制。
 * <p>
 * display list 编译窗口由录制侧分流（{@code GLListManager.buildingList} 时
 * 走 {@link EngineRenderHelper} immediate 等价路径），本命令不会出现在
 * 编译窗口内（见 {@link github.kasuminova.ssoptimizer.common.render.queue.NativeRenderCommand}
 * 契约）。
 */
public final class EngineNativeRenderCommand extends NativeRenderCommandBase {
    /** 扁平化命令条数（{@link EngineInstanceCollector#flatten} 返回值）。 */
    private final int commandCount;
    /** 顶点环形 VBO 的本次容量需求（执行侧 ensureCapacity 预检用）。 */
    private final int vertexBytes;
    /** 索引环形 VBO 的本次容量需求。 */
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

    /**
     * 录制侧装配：扁平化批次并产生命令（编码缓冲借自共享快照池）。
     * 抽成静态工厂以便单测不触碰 {@link EngineBatchImpl} 的游戏对象读取路径。
     */
    public static EngineNativeRenderCommand of(final EngineInstanceCollector.CollectedBatch batch) {
        final int requiredBytes = EngineInstanceCollector.flattenedBytes(batch);
        final ByteBuffer encoded = GlDispatch.snapshotPool().borrow(requiredBytes);
        final int commandCount = EngineInstanceCollector.flatten(batch, encoded);
        return new EngineNativeRenderCommand(encoded, commandCount,
                EngineInstanceCollector.expandedVertexBytes(batch),
                EngineInstanceCollector.expandedIndexBytes(batch));
    }
}
