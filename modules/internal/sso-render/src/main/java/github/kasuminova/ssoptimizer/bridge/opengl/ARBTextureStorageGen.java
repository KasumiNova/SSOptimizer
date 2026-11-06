package github.kasuminova.ssoptimizer.bridge.opengl;
public class ARBTextureStorageGen {
    protected ARBTextureStorageGen() {
    }

    public static void glTexStorage1D(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage.glTexStorage1D(p0, p1, p2, p3));
    }

    public static void glTexStorage2D(int p0, int p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage.glTexStorage2D(p0, p1, p2, p3, p4));
    }

    public static void glTexStorage3D(int p0, int p1, int p2, int p3, int p4, int p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage.glTexStorage3D(p0, p1, p2, p3, p4, p5));
    }

    public static void glTextureStorage1DEXT(int p0, int p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage.glTextureStorage1DEXT(p0, p1, p2, p3, p4));
    }

    public static void glTextureStorage2DEXT(int p0, int p1, int p2, int p3, int p4, int p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage.glTextureStorage2DEXT(p0, p1, p2, p3, p4, p5));
    }

    public static void glTextureStorage3DEXT(int p0, int p1, int p2, int p3, int p4, int p5, int p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage.glTextureStorage3DEXT(p0, p1, p2, p3, p4, p5, p6));
    }
}
