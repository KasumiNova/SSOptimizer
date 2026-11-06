package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTTimerQuery {
    private EXTTimerQuery() {
    }

    public static long glGetQueryObjectEXT(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.EXTTimerQuery.glGetQueryObjectEXT(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetQueryObjectEXT(int p0, int p1, java.nio.LongBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(p2, 16, buf -> org.lwjgl.opengl.EXTTimerQuery.glGetQueryObjectEXT(p0, p1, buf)));
    }

    public static long glGetQueryObjectuEXT(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.EXTTimerQuery.glGetQueryObjectuEXT(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetQueryObjectuEXT(int p0, int p1, java.nio.LongBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(p2, 16, buf -> org.lwjgl.opengl.EXTTimerQuery.glGetQueryObjectuEXT(p0, p1, buf)));
    }
}
