package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.LWJGLException;

/**
 * Display 本体 drawable 的登记壳。真实 GL 上下文仅归渲染线程持有，本类不产生 GL 命令。
 */
final class DisplayDrawable implements Drawable {
    static final DisplayDrawable INSTANCE = new DisplayDrawable();

    private volatile boolean destroyed;

    private DisplayDrawable() {
    }

    @Override
    public void makeCurrent() throws LWJGLException {
        if (destroyed) {
            throw new IllegalStateException("[SSOptimizer] Display drawable 已销毁，仍尝试 makeCurrent");
        }
    }

    @Override
    public void releaseContext() throws LWJGLException {
    }

    @Override
    public boolean isCurrent() throws LWJGLException {
        return false;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }
}
