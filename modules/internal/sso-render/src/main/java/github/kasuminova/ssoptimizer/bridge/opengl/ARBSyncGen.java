package github.kasuminova.ssoptimizer.bridge.opengl;
public class ARBSyncGen {
    protected ARBSyncGen() {
    }

    public static long glGetInteger64(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSync.glGetInteger64(p0));
    }

    public static void glGetInteger64(int p0, java.nio.LongBuffer p1) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(p1, 16, buf -> org.lwjgl.opengl.ARBSync.glGetInteger64(p0, buf)));
    }
}
