// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVTextureBarrier {
    private NVTextureBarrier() {
    }

    public static void glTextureBarrierNV() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVTextureBarrier.glTextureBarrierNV());
    }
}
