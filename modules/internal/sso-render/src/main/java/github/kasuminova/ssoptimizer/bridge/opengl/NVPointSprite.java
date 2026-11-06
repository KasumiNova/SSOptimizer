// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVPointSprite {
    private NVPointSprite() {
    }

    public static void glPointParameterNV(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.NVPointSprite.glPointParameterNV(p0, snapshot.asIntBuffer()));
    }

    public static void glPointParameteriNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVPointSprite.glPointParameteriNV(p0, p1));
    }
}
