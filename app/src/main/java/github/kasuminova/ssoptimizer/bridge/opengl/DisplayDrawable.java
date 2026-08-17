package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.LWJGLException;

/**
 * {@link Display#getDrawable()} 的 bridge 返回值：Display 本体 drawable 的登记壳。
 * <p>
 * 折叠模型下（见 {@link SharedDrawable} 类 javadoc）Display 的 drawable 不再对应
 * 可被模组线程 makeCurrent 的真实上下文——真实上下文唯一归渲染线程持有。本类
 * 只提供「登记/失效」语义，让模组 {@code Display.getDrawable()} →
 * {@code new SharedDrawable(drawable)} 的调用链类型与行为一致走通。
 */
final class DisplayDrawable implements Drawable {
    /** 进程级单例：Display drawable 在真实 LWJGL 中同样唯一。 */
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
        // 折叠模型下无真实上下文关联，登记语义为空操作
    }

    @Override
    public boolean isCurrent() throws LWJGLException {
        // 真实上下文恒在渲染线程上 current；其余线程永远没有 context
        return false;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }
}
