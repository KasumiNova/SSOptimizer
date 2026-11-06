package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ATIEnvmapBumpmap {
    private ATIEnvmapBumpmap() {
    }

    public static void glGetTexBumpParameterATI(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p1, 16, buf -> org.lwjgl.opengl.ATIEnvmapBumpmap.glGetTexBumpParameterATI(p0, buf)));
    }

    public static void glGetTexBumpParameterATI(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p1, 16, buf -> org.lwjgl.opengl.ATIEnvmapBumpmap.glGetTexBumpParameterATI(p0, buf)));
    }

    public static void glTexBumpParameterATI(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ATIEnvmapBumpmap.glTexBumpParameterATI(p0, snapshot.asFloatBuffer()));
    }

    public static void glTexBumpParameterATI(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ATIEnvmapBumpmap.glTexBumpParameterATI(p0, snapshot.asIntBuffer()));
    }
}
