package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.IntBuffer;

/**
 * org.lwjgl.opengl.EXTFramebufferObject 的 bridge 镜像。
 * <p>
 * 动机同 {@link GL11}。盘点结论：游戏本体 com.fs.graphics.FrameBufferObject 与
 * SSOptimizer RadarCompositeCache 走 EXT 路径；GraphicsLib 在 EXT/ARB/GL30 三条
 * 路径间运行时选择，三者必须全部覆盖。LWJGL2 中这三个入口是同功能的扩展/核心
 * 别名，本类与 {@link ARBFramebufferObject}/{@link GL30} 的 FBO 族方法一一对应，
 * 各自命令体调各自的真实函数。
 * <p>
 * id 分配（glGenFramebuffersEXT/glGenRenderbuffersEXT）与状态查询
 * （glCheckFramebufferStatusEXT）走阻塞通道，语义同 {@link GL11} 的 getter。
 */
public final class EXTFramebufferObject {
    private EXTFramebufferObject() {
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

    public static void glBindFramebufferEXT(int target, int framebuffer) {
        if (target == org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT) {
            // 录制侧跟踪 FRAMEBUFFER 绑定：bridge GL11.glGetInteger 的
            // GL_FRAMEBUFFER_BINDING_EXT 短路读取该值，免阻塞往返（雷达合成缓存等
            // 保存/恢复路径每帧使用）
            BridgeSupport.setFramebufferBinding(framebuffer);
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(target, framebuffer));
    }

    /** 资源分配：阻塞通道取回真实 FBO id。 */
    public static int glGenFramebuffersEXT() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.EXTFramebufferObject::glGenFramebuffersEXT);
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenFramebuffersEXT(IntBuffer framebuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.EXTFramebufferObject.glGenFramebuffersEXT(framebuffers));
    }

    public static void glDeleteFramebuffersEXT(int framebuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer));
    }

    public static void glDeleteFramebuffersEXT(IntBuffer framebuffers) {
        BridgeSupport.enqueueSnapshot(framebuffers, snapshot ->
                org.lwjgl.opengl.EXTFramebufferObject.glDeleteFramebuffersEXT(snapshot.asIntBuffer()));
    }

    /** 状态查询：阻塞通道取回（FBO 完整性校验语义强依赖执行完成）。 */
    public static int glCheckFramebufferStatusEXT(int target) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.EXTFramebufferObject.glCheckFramebufferStatusEXT(target));
    }

    public static void glFramebufferTexture2DEXT(int target, int attachment, int textarget, int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glFramebufferTexture2DEXT(target, attachment, textarget, texture, level));
    }

    public static void glFramebufferRenderbufferEXT(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glFramebufferRenderbufferEXT(target, attachment, renderbuffertarget, renderbuffer));
    }

    public static void glGenerateMipmapEXT(int target) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glGenerateMipmapEXT(target));
    }

    public static void glBindRenderbufferEXT(int target, int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glBindRenderbufferEXT(target, renderbuffer));
    }

    /** 资源分配：阻塞通道取回真实 renderbuffer id。 */
    public static int glGenRenderbuffersEXT() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.EXTFramebufferObject::glGenRenderbuffersEXT);
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenRenderbuffersEXT(IntBuffer renderbuffers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.EXTFramebufferObject.glGenRenderbuffersEXT(renderbuffers));
    }

    public static void glDeleteRenderbuffersEXT(int renderbuffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject.glDeleteRenderbuffersEXT(renderbuffer));
    }

    public static void glDeleteRenderbuffersEXT(IntBuffer renderbuffers) {
        BridgeSupport.enqueueSnapshot(renderbuffers, snapshot ->
                org.lwjgl.opengl.EXTFramebufferObject.glDeleteRenderbuffersEXT(snapshot.asIntBuffer()));
    }

    public static void glRenderbufferStorageEXT(int target, int internalformat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTFramebufferObject
                .glRenderbufferStorageEXT(target, internalformat, width, height));
    }
}
