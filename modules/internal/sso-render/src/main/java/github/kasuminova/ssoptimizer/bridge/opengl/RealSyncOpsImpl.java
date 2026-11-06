package github.kasuminova.ssoptimizer.bridge.opengl;

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

    @Override
    public void memoryBarrier(final int barriers) {
        org.lwjgl.opengl.GL42.glMemoryBarrier(barriers);
    }
}
