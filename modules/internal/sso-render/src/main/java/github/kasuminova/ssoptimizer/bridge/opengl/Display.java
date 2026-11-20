package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.automation.FrameCaptureHook;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.PixelFormat;

import java.nio.ByteBuffer;

/** LWJGL 窗口由渲染线程创建。 */
public final class Display {
    private static Object presentationContext;
    private static long normalPresentationSequence;
    private static boolean overlayPending;
    private static boolean overlayPresented;

    private Display() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    /** 创建 LWJGL 窗口并等待渲染线程初始化上下文。 */
    public static void create() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create();
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void create(PixelFormat pixelFormat) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    private static void fireFrameCaptureHook() {
        final FrameCaptureHook hook = ServiceRegistry.getOrNull(FrameCaptureHook.class);
        if (hook != null) {
            hook.onDisplayUpdate();
        }
    }

    public static void update() {
        update(true);
    }

    public static void update(boolean processMessages) {
        RenderQueue q = BridgeSupport.queue();
        BridgeSupport.flushVertexStream();
        q.submit(() -> {
            BridgeSupport.refillBufferIdStashIfLow();
            fireFrameCaptureHook();
            final boolean visible = org.lwjgl.opengl.Display.isVisible();
            org.lwjgl.opengl.Display.update(processMessages);
            onPresentation(visible);
        });
        BridgeSupport.swapFramesAndSync();
    }

    static long markSaveOverlayFrame() {
        resetPresentationIfContextChanged();
        overlayPending = true;
        return overlayPresented ? 0 : normalPresentationSequence;
    }

    private static void onPresentation(final boolean swapped) {
        resetPresentationIfContextChanged();
        if (swapped) {
            overlayPresented = overlayPending;
            if (!overlayPresented) {
                normalPresentationSequence++;
            }
            overlayPending = false;
        } else if (overlayPending) {
            // 隐藏窗口仍可能因 dirty 标志交换缓冲，禁止将该前缓冲标记为普通帧。
            overlayPresented = true;
        }
    }

    private static void resetPresentationIfContextChanged() {
        final Object token = BridgeSupport.mainGlContextToken();
        if (presentationContext != token) {
            presentationContext = token;
            normalPresentationSequence = 0;
            overlayPending = false;
            overlayPresented = false;
        }
    }

    /** Win32 只派发调用线程的窗口消息，失焦时游戏循环可能只调用此方法。 */
    public static void processMessages() {
        BridgeSupport.queue().waitUncounted(org.lwjgl.opengl.Display::processMessages);
    }

    public static boolean isCloseRequested() {
        return org.lwjgl.opengl.Display.isCloseRequested();
    }

    public static boolean isActive() {
        return org.lwjgl.opengl.Display.isActive();
    }

    public static boolean isVisible() {
        return org.lwjgl.opengl.Display.isVisible();
    }

    public static boolean isCreated() {
        return org.lwjgl.opengl.Display.isCreated();
    }

    public static boolean isDirty() {
        return org.lwjgl.opengl.Display.isDirty();
    }

    public static float getPixelScaleFactor() {
        return org.lwjgl.opengl.Display.getPixelScaleFactor();
    }

    public static Drawable getDrawable() {
        return DisplayDrawable.INSTANCE;
    }

    public static boolean isFullscreen() {
        return org.lwjgl.opengl.Display.isFullscreen();
    }

    public static DisplayMode getDisplayMode() {
        return org.lwjgl.opengl.Display.getDisplayMode();
    }

    public static int getWidth() {
        return org.lwjgl.opengl.Display.getWidth();
    }

    public static int getHeight() {
        return org.lwjgl.opengl.Display.getHeight();
    }

    /** 帧率限制只暂停调用线程。 */
    public static void sync(int fps) {
        org.lwjgl.opengl.Display.sync(fps);
    }

    public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
        return BridgeSupport.blockingGetLwjgl(org.lwjgl.opengl.Display::getAvailableDisplayModes);
    }

    public static DisplayMode getDesktopDisplayMode() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.Display::getDesktopDisplayMode);
    }

    public static void setDisplayMode(DisplayMode mode) throws LWJGLException {
        changeWindow(() -> org.lwjgl.opengl.Display.setDisplayMode(mode));
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void setFullscreen(boolean fullscreen) throws LWJGLException {
        changeWindow(() -> org.lwjgl.opengl.Display.setFullscreen(fullscreen));
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void setTitle(String title) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setTitle(title));
    }

    public static void setLocation(int x, int y) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setLocation(x, y));
    }

    public static void setVSyncEnabled(boolean vsync) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setVSyncEnabled(vsync));
    }

    public static void makeCurrent() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            clearIfDisplayNotCurrent();
            org.lwjgl.opengl.Display.makeCurrent();
        });
    }

    public static void destroy() {
        BridgeSupport.blockingWait(() -> {
            UniformLookup.clear();
            org.lwjgl.opengl.Display.destroy();
        });
    }

    public static int setIcon(ByteBuffer[] icons) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.Display.setIcon(icons));
    }

    /** 此重载只接受 {@link #getDrawable()} 返回的实例。 */
    public static void create(PixelFormat pixelFormat, Drawable drawable) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat);
    }

    public static void create(PixelFormat pixelFormat,
                              org.lwjgl.opengl.ContextAttribs attribs) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat, attribs);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void create(PixelFormat pixelFormat, Drawable drawable,
                              org.lwjgl.opengl.ContextAttribs attribs) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat, attribs);
    }

    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat,
                              org.lwjgl.opengles.ContextAttribs attribs) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat, attribs);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat,
                              Drawable drawable) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat);
    }

    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat, Drawable drawable,
                              org.lwjgl.opengles.ContextAttribs attribs) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat);
    }

    private static void requireDisplayDrawable(Drawable drawable) {
        if (drawable != DisplayDrawable.INSTANCE) {
            throw new IllegalArgumentException(
                    "[SSOptimizer] Display.create 的 drawable 参数只支持 Display.getDrawable()"
                            + " 的本体单例（折叠模型下不存在可创建 Display 的自定义 drawable），实际: "
                            + drawable);
        }
    }

    public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
        changeWindow(() -> org.lwjgl.opengl.Display.setDisplayModeAndFullscreen(mode));
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void setDisplayConfiguration(float gamma, float brightness,
                                               float contrast) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(
                () -> org.lwjgl.opengl.Display.setDisplayConfiguration(gamma, brightness, contrast));
    }

    public static void swapBuffers() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.swapBuffers();
            onPresentation(true);
        });
    }

    public static void releaseContext() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            UniformLookup.clear();
            org.lwjgl.opengl.Display.releaseContext();
        });
    }

    public static boolean isCurrent() throws LWJGLException {
        return BridgeSupport.blockingGetLwjgl(org.lwjgl.opengl.Display::isCurrent);
    }

    public static void setParent(java.awt.Canvas parent) throws LWJGLException {
        changeWindow(() -> org.lwjgl.opengl.Display.setParent(parent));
    }

    private static void clearIfDisplayNotCurrent() {
        try {
            if (org.lwjgl.opengl.Display.isCreated() && org.lwjgl.opengl.Display.isCurrent()) {
                return;
            }
        } catch (LWJGLException | RuntimeException failure) {
            org.apache.log4j.Logger.getLogger(Display.class).warn("[SSOptimizer] 查询当前 Display 上下文失败，清除 uniform 位置缓存", failure);
        }
        UniformLookup.clear();
    }

    private static void changeWindow(BridgeSupport.ThrowingTask change) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            if (org.lwjgl.opengl.Display.isCreated()) {
                clearIfDisplayNotCurrent();
            }
            try {
                change.run();
            } catch (LWJGLException | RuntimeException | Error failure) {
                // LWJGL 复用原上下文创建窗口，失败时可能已经销毁 drawable。
                UniformLookup.clear();
                throw failure;
            }
        });
    }

    public static String getTitle() {
        return org.lwjgl.opengl.Display.getTitle();
    }

    public static java.awt.Canvas getParent() {
        return org.lwjgl.opengl.Display.getParent();
    }

    public static void setInitialBackground(float red, float green, float blue) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setInitialBackground(red, green, blue));
    }

    public static void setSwapInterval(int interval) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setSwapInterval(interval));
    }

    public static void setResizable(boolean resizable) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setResizable(resizable));
    }

    public static boolean isResizable() {
        return org.lwjgl.opengl.Display.isResizable();
    }

    public static boolean wasResized() {
        return org.lwjgl.opengl.Display.wasResized();
    }

    public static int getX() {
        return org.lwjgl.opengl.Display.getX();
    }

    public static int getY() {
        return org.lwjgl.opengl.Display.getY();
    }

    public static String getAdapter() {
        return org.lwjgl.opengl.Display.getAdapter();
    }

    public static String getVersion() {
        return org.lwjgl.opengl.Display.getVersion();
    }
}
