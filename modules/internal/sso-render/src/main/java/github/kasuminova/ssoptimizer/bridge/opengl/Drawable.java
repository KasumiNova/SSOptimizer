package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.LWJGLException;

/**
 * org.lwjgl.opengl.Drawable 的 bridge 门面（接口形态与 LWJGL 一致）。
 * <p>
 * 动机：BoxUtil 的 ShaderCore 持有 Drawable 引用并在自家线程上调
 * makeCurrent/releaseContext/destroy。ASM 重定向把
 * {@code org/lwjgl/opengl/Drawable} 的 INVOKEINTERFACE owner 改写到本接口，
 * 因此保持接口形态与签名（含受检异常声明），调用点零适配。
 * <p>
 * 折叠语义见 {@link SharedDrawable}：aux-context 不再对应真实 GL 上下文，
 * 本接口的方法在 bridge 实现里只做登记，不产生 GL 命令。
 */
public interface Drawable {
    /**
     * 把本 drawable 标记为当前线程的活跃绘制目标。折叠模型下不产生 GL 命令，
     * 仅登记（模组拿到返回值后即认为「上下文已就绪」，其后的 GL 调用本就走
     * 命令队列按提交序执行）。
     *
     * @throws LWJGLException 签名与 LWJGL 对齐；bridge 实现实际不抛出
     */
    void makeCurrent() throws LWJGLException;

    /**
     * 解除当前线程与本 drawable 的关联标记。折叠模型下不产生 GL 命令。
     *
     * @throws LWJGLException 签名与 LWJGL 对齐；bridge 实现实际不抛出
     */
    void releaseContext() throws LWJGLException;

    /**
     * @return 本 drawable 是否登记在当前线程上
     * @throws LWJGLException 签名与 LWJGL 对齐；bridge 实现实际不抛出
     */
    boolean isCurrent() throws LWJGLException;

    /** 销毁标记：登记失效，后续 makeCurrent 拒绝。 */
    void destroy();
}
