package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * ARB 同步入口，统一委托 {@link GL32} 以复用同步对象语义。
 */
public final class ARBSync extends ARBSyncGen {
    private ARBSync() {
    }

    public static GLSync glFenceSync(int condition, int flags) {
        return GL32.glFenceSync(condition, flags);
    }

    public static boolean glIsSync(GLSync sync) {
        return GL32.glIsSync(sync);
    }

    public static void glDeleteSync(GLSync sync) {
        GL32.glDeleteSync(sync);
    }

    public static int glClientWaitSync(GLSync sync, int flags, long timeout) {
        return GL32.glClientWaitSync(sync, flags, timeout);
    }

    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        GL32.glWaitSync(sync, flags, timeout);
    }

    public static void glGetSync(GLSync sync, int pname, java.nio.IntBuffer length,
                                 java.nio.IntBuffer values) {
        GL32.glGetSync(sync, pname, length, values);
    }

    public static int glGetSync(GLSync sync, int pname) {
        return GL32.glGetSync(sync, pname);
    }

    public static int glGetSynci(GLSync sync, int pname) {
        return GL32.glGetSynci(sync, pname);
    }

    public static long glGetInteger64(int pname) {
        return GL32.glGetInteger64(pname);
    }

    public static void glGetInteger64(int pname, java.nio.LongBuffer params) {
        GL32.glGetInteger64(pname, params);
    }
}
