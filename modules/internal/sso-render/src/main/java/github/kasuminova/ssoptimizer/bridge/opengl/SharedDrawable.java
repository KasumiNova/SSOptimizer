package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;
import org.lwjgl.LWJGLException;

/** makeCurrent 成功后当前线程使用真实共享上下文并绕过渲染队列；主上下文重建后调用方必须重建本对象。 */
public final class SharedDrawable implements Drawable {
    private static final Logger LOGGER = Logger.getLogger(SharedDrawable.class);

    interface RealSharedContext {
        void makeCurrent() throws LWJGLException;

        void releaseContext() throws LWJGLException;

        void destroy() throws LWJGLException;
    }

    /** 仅在渲染线程调用。 */
    interface RealSharedContextFactory {
        RealSharedContext create() throws LWJGLException;
    }

    static volatile RealSharedContextFactory realContextFactory =
            () -> new LwjglSharedContext(new org.lwjgl.opengl.SharedDrawable(
                    org.lwjgl.opengl.Display.getDrawable()));

    private static final class LwjglSharedContext implements RealSharedContext {
        private final org.lwjgl.opengl.SharedDrawable delegate;

        private LwjglSharedContext(final org.lwjgl.opengl.SharedDrawable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void makeCurrent() throws LWJGLException {
            delegate.makeCurrent();
        }

        @Override
        public void releaseContext() throws LWJGLException {
            delegate.releaseContext();
        }

        @Override
        public void destroy() throws LWJGLException {
            delegate.destroy();
        }
    }

    private final Drawable parent;
    private volatile RealSharedContext real;
    private volatile Thread currentThread;
    private volatile boolean destroyed;

    public SharedDrawable(Drawable parent) throws LWJGLException {
        this.parent = parent;
    }

    static void realContextFactoryForTesting(final RealSharedContextFactory factory) {
        realContextFactory = factory;
    }

    public Drawable parent() {
        return parent;
    }

    @Override
    public void makeCurrent() throws LWJGLException {
        if (destroyed) {
            throw new IllegalStateException("[SSOptimizer] SharedDrawable 已销毁，模组线程仍尝试 makeCurrent");
        }
        RealSharedContext ctx = real;
        if (ctx == null) {
            synchronized (this) {
                ctx = real;
                if (ctx == null) {
                    // 创建在渲染线程串行执行，makeCurrent 必须在调用线程执行。
                    final RealSharedContext created =
                            BridgeSupport.blockingGetLwjgl(realContextFactory::create);
                    created.makeCurrent();
                    real = created;
                    ctx = created;
                    LOGGER.info("[SSOptimizer] aux 线程 \"" + Thread.currentThread().getName()
                            + "\" 已持有真实共享 GL 上下文，其 GL 调用切换为原生直执（不再经渲染队列）");
                }
            }
        } else {
            ctx.makeCurrent();
        }
        currentThread = Thread.currentThread();
        BridgeSupport.recordingContext().auxNative = true;
    }

    @Override
    public void releaseContext() throws LWJGLException {
        final RealSharedContext ctx = real;
        if (ctx != null && currentThread == Thread.currentThread()) {
            ctx.releaseContext();
        }
        currentThread = null;
        BridgeSupport.recordingContext().auxNative = false;
    }

    @Override
    public boolean isCurrent() {
        return !destroyed && currentThread == Thread.currentThread();
    }

    @Override
    public void destroy() {
        destroyed = true;
        final RealSharedContext ctx = real;
        if (ctx != null) {
            real = null;
            try {
                ctx.destroy();
            } catch (LWJGLException e) {
                throw new IllegalStateException("[SSOptimizer] 销毁真实共享 GL 上下文失败", e);
            }
        }
        if (currentThread == Thread.currentThread()) {
            BridgeSupport.recordingContext().auxNative = false;
        }
        currentThread = null;
    }
}
