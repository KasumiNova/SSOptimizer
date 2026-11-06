package github.kasuminova.ssoptimizer.bridge.opengl;

public final class GL21 {
    private GL21() {
    }

    public static void glUniformMatrix2x3(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL21.glUniformMatrix2x3(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix2x4(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL21.glUniformMatrix2x4(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix3x2(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL21.glUniformMatrix3x2(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix3x4(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL21.glUniformMatrix3x4(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix4x2(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL21.glUniformMatrix4x2(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix4x3(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL21.glUniformMatrix4x3(p0, p1, snapshot.asFloatBuffer()));
    }
}
