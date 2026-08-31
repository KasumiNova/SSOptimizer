package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * {@link RealSyncOps} 的默认实现：直通真实 {@code org.lwjgl.opengl.GL32}。
 * 只在持有 GL 上下文的线程上被调用（渲染线程或 aux 原生线程）。
 * 句柄为不透明 Object（LWJGL2 的 {@code org.lwjgl.opengl.GLSync} 构造器包级
 * 私有，外部包无法实例化类型引用），实现内 cast 回真实类型。
 */
final class RealSyncOpsImpl implements RealSyncOps {
    static final RealSyncOpsImpl INSTANCE = new RealSyncOpsImpl();

    private RealSyncOpsImpl() {
    }

    @Override
    public Object fenceSync(final int condition, final int flags) {
        return org.lwjgl.opengl.GL32.glFenceSync(condition, flags);
    }

    @Override
    public void waitSync(final Object sync, final int flags, final long timeout) {
        org.lwjgl.opengl.GL32.glWaitSync((org.lwjgl.opengl.GLSync) sync, flags, timeout);
    }

    @Override
    public int clientWaitSync(final Object sync, final int flags, final long timeout) {
        return org.lwjgl.opengl.GL32.glClientWaitSync((org.lwjgl.opengl.GLSync) sync, flags, timeout);
    }

    @Override
    public void deleteSync(final Object sync) {
        org.lwjgl.opengl.GL32.glDeleteSync((org.lwjgl.opengl.GLSync) sync);
    }
}
