package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBSampleShading {
    private ARBSampleShading() {
    }

    public static void glMinSampleShadingARB(float p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSampleShading.glMinSampleShadingARB(p0));
    }
}
