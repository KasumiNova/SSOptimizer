package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * org.lwjgl.opengl.ARBSync 的 bridge 镜像（sync 身份族全量，委托 {@link GL32}）。
 * <p>
 * 动机：BoxUtil Operation$Sync 在不支持 OpenGL32 的上下文上走 GL_ARB_sync 回退
 * 分支，以方法引用 {@code ARBSync::glFenceSync} 等挂 lambda。ARBSync 未入改写表时
 * 这些 indy 的 impl Handle 保持 lwjgl owner，而 instantiatedMethodType 里的身份
 * 类型（GLSync）会被改写——两侧类型不一致，该回退分支一旦执行即
 * LambdaConversionException（与 GL32.glGetSync buffer 形态未镜像同一崩溃签名）。
 * sync 身份族在折叠模型下是纯 Java 会合点（见 {@link GL32} 类 javadoc），
 * 本类只做入口对齐，语义全部由 {@link GL32} 承载。
 */
public final class ARBSync {
    private ARBSync() {
    }

    /** @see GL32#glFenceSync */
    public static GLSync glFenceSync(int condition, int flags) {
        return GL32.glFenceSync(condition, flags);
    }

    /** @see GL32#glIsSync */
    public static boolean glIsSync(GLSync sync) {
        return GL32.glIsSync(sync);
    }

    /** @see GL32#glDeleteSync */
    public static void glDeleteSync(GLSync sync) {
        GL32.glDeleteSync(sync);
    }

    /** @see GL32#glClientWaitSync */
    public static int glClientWaitSync(GLSync sync, int flags, long timeout) {
        return GL32.glClientWaitSync(sync, flags, timeout);
    }

    /** @see GL32#glWaitSync */
    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        GL32.glWaitSync(sync, flags, timeout);
    }

    /** @see GL32#glGetSync(GLSync, int, java.nio.IntBuffer, java.nio.IntBuffer) */
    public static void glGetSync(GLSync sync, int pname, java.nio.IntBuffer length,
                                 java.nio.IntBuffer values) {
        GL32.glGetSync(sync, pname, length, values);
    }

    /** @see GL32#glGetSync(GLSync, int) */
    public static int glGetSync(GLSync sync, int pname) {
        return GL32.glGetSync(sync, pname);
    }

    /** @see GL32#glGetSynci */
    public static int glGetSynci(GLSync sync, int pname) {
        return GL32.glGetSynci(sync, pname);
    }
}
