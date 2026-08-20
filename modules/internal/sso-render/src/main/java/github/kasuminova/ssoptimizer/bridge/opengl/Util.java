package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.opengl.OpenGLException;

/**
 * org.lwjgl.opengl.Util 的 bridge 镜像（错误检查入口）。
 * <p>
 * 动机：BoxUtil 的 shader/纹理校验路径调 {@code Util.checkGLError()}；LWJGL
 * 原实现内部直接调真实 glGetError，在无 context 的调用线程会抛
 * 「No OpenGL context」。bridge 版经阻塞通道在渲染线程取回真实错误码，
 * 语义与原版一致（检测到错误即抛 {@link OpenGLException}）。
 */
public final class Util {
    private Util() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    /**
     * 阻塞通道取回真实 glGetError；非 {@code GL_NO_ERROR} 时抛 {@link OpenGLException}。
     *
     * @throws OpenGLException 渲染线程报告了 GL 错误
     */
    public static void checkGLError() throws OpenGLException {
        int error = BridgeSupport.blockingGet(org.lwjgl.opengl.GL11::glGetError);
        if (error != org.lwjgl.opengl.GL11.GL_NO_ERROR) {
            throw new OpenGLException(error);
        }
    }
}
