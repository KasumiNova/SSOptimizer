package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.LWJGLException;

/**
 * org.lwjgl.opengl.SharedDrawable 的 bridge 门面。
 * <p>
 * 动机与折叠设计：真实 LWJGL 中 SharedDrawable 为模组后台线程创建共享 GL
 * 上下文（BoxUtil 的渲染/逻辑/逻辑辅助三个线程各持一个）。在渲染/逻辑分离
 * 架构里，所有 GL 调用已被 ASM 重定向为命令录制——模组线程上的「GL 调用」
 * 本来就走 {@code RenderQueue.submit} 按提交序进入唯一渲染线程执行，
 * <strong>不需要、也不能再创建真实 aux context</strong>（主线程与模组线程都
 * 没有 context，创建会崩）。因此本门面把 SharedDrawable 折叠为纯登记对象：
 * <ul>
 *   <li>构造：记录父 drawable 引用，不触碰任何 GL/X11 资源；</li>
 *   <li>makeCurrent/releaseContext/isCurrent：登记「当前线程已就位」标记，
 *       供模组的 makeCurrent + glGetError 健康校验模式正常走通
 *       （glGetError 走阻塞 getter 通道，语义仍真实）；</li>
 *   <li>destroy：登记失效，此后 makeCurrent 抛 {@link IllegalStateException}。</li>
 * </ul>
 * 限制（javadoc 于盘点文档「BoxUtil 初版声明不兼容」一节亦有声明）：模组若
 * 依赖 context 隔离的驱动状态（各自独立的默认 FBO、上下文私有对象表、
 * 多线程并行提交 GPU 命令流），本折叠模型不适用——所有模组线程的命令在单
 * 渲染线程上共享同一份 GL 状态，交错提交会互相污染。
 */
public final class SharedDrawable implements Drawable {
    private final Drawable parent;
    private volatile Thread currentThread;
    private volatile boolean destroyed;

    /**
     * @param parent 共享来源 drawable（真实 LWJGL 语义为父上下文；折叠模型下
     *               仅登记，不做资源共享）
     * @throws LWJGLException 签名与 LWJGL 对齐；本实现实际不抛出
     */
    public SharedDrawable(Drawable parent) throws LWJGLException {
        this.parent = parent;
    }

    /**
     * @return 构造时登记的父 drawable（诊断用）
     */
    public Drawable parent() {
        return parent;
    }

    @Override
    public void makeCurrent() {
        if (destroyed) {
            throw new IllegalStateException("[SSOptimizer] SharedDrawable 已销毁，模组线程仍尝试 makeCurrent");
        }
        currentThread = Thread.currentThread();
    }

    @Override
    public void releaseContext() {
        currentThread = null;
    }

    @Override
    public boolean isCurrent() {
        return !destroyed && currentThread == Thread.currentThread();
    }

    @Override
    public void destroy() {
        destroyed = true;
        currentThread = null;
    }
}
