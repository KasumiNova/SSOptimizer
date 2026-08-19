package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.nio.ByteBuffer;

/**
 * 引擎合批 flush 的 native 入口：单次 JNI 完成一整艘船的引擎绘制
 * （条带 / 核心 / 辉光三个阶段全部纹理分组）。
 * <p>
 * Java 侧负责把 {@link EngineInstanceCollector.CollectedBatch} 扁平化为命令表 +
 * 定长实例数组（布局常量见 {@link EngineInstanceCollector}，与
 * {@code ssoptimizer_engine_batch.cpp} 的结构体一一对应），native 侧完成
 * 顶点展开、环形 VBO 写入（glMapBufferRange 无同步映射）、逐组绘制与状态恢复。
 * <p>
 * 状态约定与 Java 路径一致：pushAttrib/pushClientAttrib 保存，绘制在当前矩阵栈内
 * 进行（不触碰矩阵），结束后恢复纹理/混合/VBO 绑定与 client state。
 * VBO 绑定全程在 native 内恢复为进入时的值，不经 LWJGL——StateTracker 因此
 * 始终与真实状态一致（绑定从未经 LWJGL 变更过）。
 */
public final class EngineBatchNative {
    static {
        NativeRuntime.ensureLoaded();
    }

    private EngineBatchNative() {
    }

    /**
     * 执行一个批次的全部绘制。
     *
     * @param commandBuffer     扁平化命令缓冲（direct、nativeOrder），见
     *                          {@link EngineInstanceCollector#flatten}
     * @param commandCount      命令条数
     * @param vertexVboId       顶点环形 VBO ID
     * @param vertexCapacity    顶点 VBO 容量（字节，调用方已 ensureCapacity）
     * @param vertexWriteOffset 顶点 VBO 当前写入偏移
     * @param indexVboId        索引环形 VBO ID
     * @param indexCapacity     索引 VBO 容量（字节）
     * @param indexWriteOffset  索引 VBO 当前写入偏移
     * @return 新的写入偏移：高 32 位为顶点 VBO 偏移，低 32 位为索引 VBO 偏移
     */
    public static native long nativeFlushBatch(ByteBuffer commandBuffer, int commandCount,
                                               int vertexVboId, int vertexCapacity, int vertexWriteOffset,
                                               int indexVboId, int indexCapacity, int indexWriteOffset);
}
