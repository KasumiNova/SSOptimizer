package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.LWJGLException;

/**
 * LWJGL drawable 的桥接签名。实现只维护登记状态，不向调用线程关联真实 GL 上下文。
 */
public interface Drawable {
    void makeCurrent() throws LWJGLException;

    void releaseContext() throws LWJGLException;

    boolean isCurrent() throws LWJGLException;

    void destroy();
}
