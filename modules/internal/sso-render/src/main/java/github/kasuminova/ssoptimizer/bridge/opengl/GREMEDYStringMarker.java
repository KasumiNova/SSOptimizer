// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class GREMEDYStringMarker {
    private GREMEDYStringMarker() {
    }

    public static void glStringMarkerGREMEDY(java.lang.CharSequence p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GREMEDYStringMarker.glStringMarkerGREMEDY(p0));
    }

    public static void glStringMarkerGREMEDY(java.nio.ByteBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.GREMEDYStringMarker.glStringMarkerGREMEDY(snapshot));
    }
}
