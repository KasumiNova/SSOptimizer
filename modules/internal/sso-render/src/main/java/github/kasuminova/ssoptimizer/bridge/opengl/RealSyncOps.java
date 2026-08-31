package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * 真实 GL sync 对象操作 seam：SharedDrawable 解折叠后，fence 的 GPU 侧载体
 * （跨上下文命令流排序）由真实 {@code org.lwjgl.opengl.GLSync} 承载。
 * <p>
 * sync 句柄以 {@link Object} 不透明传递：真实类型是
 * {@code org.lwjgl.opengl.GLSync}（其构造器包级私有，无 GL 环境的单测无法
 * 实例化），只有默认实现 {@link RealSyncOpsImpl} 解引用（cast）它；
 * 单测经 {@link BridgeSupport#syncOpsForTesting(RealSyncOps)} 注入假实现
 * 并以任意令牌对象充当句柄（与 {@code stateSnapshotSource} 桩同模式）。
 */
interface RealSyncOps {
    /** 在当前线程的当前上下文插入 fence 并返回真实 sync 句柄。 */
    Object fenceSync(int condition, int flags);

    /** 服务端等待（命令流排序，调用线程不阻塞）。 */
    void waitSync(Object sync, int flags, long timeout);

    /** 客户端等待（调用线程阻塞至 GPU 完成或超时），返回真实状态码。 */
    int clientWaitSync(Object sync, int flags, long timeout);

    /** 删除真实 sync 句柄。 */
    void deleteSync(Object sync);
}
