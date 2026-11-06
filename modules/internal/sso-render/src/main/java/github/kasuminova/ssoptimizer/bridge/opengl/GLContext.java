package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.opengl.ContextCapabilities;

/** 桥接上下文能力查询；能力表在同一上下文生命周期内缓存。 */
public final class GLContext {
    private static volatile ContextCapabilities capabilities;
    private static volatile boolean capabilitiesFetched;

    private GLContext() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
        capabilities = null;
        capabilitiesFetched = false;
    }

    /** 上下文重建后清空能力缓存；销毁期间保留旧缓存，避免无上下文时重新查询。 */
    static void invalidateCapabilities() {
        capabilities = null;
        capabilitiesFetched = false;
    }

    /** 首次调用在渲染线程阻塞取回并缓存，后续读取缓存。 */
    public static ContextCapabilities getCapabilities() {
        if (!capabilitiesFetched) {
            synchronized (GLContext.class) {
                if (!capabilitiesFetched) {
                    capabilities = BridgeSupport.blockingGet(org.lwjgl.opengl.GLContext::getCapabilities);
                    capabilitiesFetched = true;
                }
            }
        }
        return capabilities;
    }

    public static void useContext(Object context) throws org.lwjgl.LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.GLContext.useContext(context));
    }

    public static void useContext(Object context, boolean isSurfaceless)
            throws org.lwjgl.LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.GLContext.useContext(context, isSurfaceless));
    }

    public static void loadOpenGLLibrary() throws org.lwjgl.LWJGLException {
        BridgeSupport.blockingWaitLwjgl(org.lwjgl.opengl.GLContext::loadOpenGLLibrary);
    }

    public static void unloadOpenGLLibrary() {
        BridgeSupport.blockingWait(org.lwjgl.opengl.GLContext::unloadOpenGLLibrary);
    }
}
