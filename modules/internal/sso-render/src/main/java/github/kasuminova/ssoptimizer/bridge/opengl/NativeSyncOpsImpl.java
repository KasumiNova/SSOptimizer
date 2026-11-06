package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;

final class NativeSyncOpsImpl implements RealSyncOps {
    private static final Logger LOGGER = Logger.getLogger(NativeSyncOpsImpl.class);

    static final NativeSyncOpsImpl INSTANCE = new NativeSyncOpsImpl();

    static {
        NativeRuntime.ensureLoaded();
    }

    private NativeSyncOpsImpl() {
    }

    static boolean isSupported() {
        try {
            return nativeSyncOpsSupported();
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[SSOptimizer] Native sync support probe link failed; retaining LWJGL sync ops.", e);
            return false;
        }
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
