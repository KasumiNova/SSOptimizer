package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

/**
 * {@link RealSyncOps} 的 native 实现：sync 操作经 JNI 直调 glad 函数指针，
 * 消除 LWJGL 静态调用每次 {@code GLContext.getCapabilities()} 的 ThreadLocal
 * 能力查找（v52 profile：glMemoryBarrier 路径 ThreadLocal.get 自身 12.1%）。
 * <p>
 * 句柄以 {@link Long} 承载（GLsync 指针值），与 {@link RealSyncOpsImpl} 的
 * GLSync 对象同为不透明 Object——句柄只在 {@link RealSyncOps} 实现内部解引用，
 * 装配哪个实现决定句柄形态，两侧不混用。
 * <p>
 * 装配：仅渲染线程 glad 就绪（{@link NativeRuntime#ensureQueueGlReady()}）
 * 且能力指针齐全（{@link #isSupported()}）后由
 * {@link BridgeSupport#onRenderThreadGlContextReady()} 安装；
 * 调用线程约束同接口约定（渲染线程 / aux 原生线程）。
 */
final class NativeSyncOpsImpl implements RealSyncOps {
    static final NativeSyncOpsImpl INSTANCE = new NativeSyncOpsImpl();

    static {
        NativeRuntime.ensureLoaded();
    }

    private NativeSyncOpsImpl() {
    }

    /** 驱动能力探测：sync/memoryBarrier 函数指针全部就绪才允许装配（缺指针直调即崩）。 */
    static boolean isSupported() {
        return nativeSyncOpsSupported();
    }

    @Override
    public Object fenceSync(final int condition, final int flags) {
        return nativeFenceSync(condition, flags);
    }

    @Override
    public void waitSync(final Object sync, final int flags, final long timeout) {
        nativeWaitSync((Long) sync, flags, timeout);
    }

    @Override
    public int clientWaitSync(final Object sync, final int flags, final long timeout) {
        return nativeClientWaitSync((Long) sync, flags, timeout);
    }

    @Override
    public void deleteSync(final Object sync) {
        nativeDeleteSync((Long) sync);
    }

    @Override
    public void memoryBarrier(final int barriers) {
        nativeMemoryBarrier(barriers);
    }

    private static native boolean nativeSyncOpsSupported();

    private static native long nativeFenceSync(int condition, int flags);

    private static native void nativeWaitSync(long sync, int flags, long timeout);

    private static native int nativeClientWaitSync(long sync, int flags, long timeout);

    private static native void nativeDeleteSync(long sync);

    private static native void nativeMemoryBarrier(int barriers);
}
