package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * 引擎 native 命令的渲染线程执行器：持有环形 VBO 对与 native 写入偏移，
 * 全部方法仅渲染线程调用（{@link EngineNativeRenderCommand} 执行体），
 * 单线程 confinement 无需同步。
 * <p>
 * VBO 生命周期：首个命令执行时惰性创建（glGenBuffers 落在持有真实 context
 * 的渲染线程）；上下文重建（显示模式/全屏切换）后旧 VBO id 全部失效——
 * 注册 {@link GlDispatch#registerContextRecreatedListener} 标脏（回调线程
 * 不定，仅置 volatile 标记），下一次执行时在渲染线程重建并清零环形偏移。
 */
final class EngineNativeExecutor {
    static final EngineNativeExecutor INSTANCE = new EngineNativeExecutor();

    private static final int VERTEX_VBO_CAPACITY = 512 * 1024;
    private static final int INDEX_VBO_CAPACITY = 128 * 1024;

    private DynamicVbo vertexVbo;
    private DynamicVbo indexVbo;
    /** native 环形写入偏移（与 EngineBatchImpl 非分离路径的 native 偏移语义一致）。 */
    private int nativeVertexWriteOffset;
    private int nativeIndexWriteOffset;
    /** 上下文重建标脏（监听回调线程写、渲染线程读）。 */
    private volatile boolean contextInvalidated;

    private EngineNativeExecutor() {
    }

    void execute(final ByteBuffer encoded, final int commandCount,
                 final int vertexBytes, final int indexBytes) {
        if (vertexVbo == null || contextInvalidated) {
            recreateVbos();
        }
        // native 环形写入不扩容，容量预检在 Java 侧完成（扩容后 native 偏移同步清零）
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

    /** 渲染线程上（重）建环形 VBO 对并清零环形偏移；首次创建时注册上下文重建标脏监听。 */
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
