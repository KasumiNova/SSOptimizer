package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.util.glu.Sphere;

/**
 * GLU 工具类桥接：为 System 类加载器加载、无法经 transformer 链改写的
 * lwjgl_util 类（{@code org.lwjgl.util.glu.*}）提供渲染队列转发入口。
 * <p>
 * 动机：分离模式下主线程不持有 GL context，而 GLU 类（如 {@link Sphere}）
 * 内部直接引用真实 {@code org.lwjgl.opengl.GL11}——它们随 lwjgl_util jar 由
 * System 类加载器加载，{@code RenderThreadRedirectTransformer} 处理不到，
 * 主线程直接调用会以 "No OpenGL context found in the current thread" 崩溃。
 * 本类把这些调用整段作为一条命令提交进渲染队列，在持有真实 context 的
 * 渲染线程上执行；调用点前后的 bridged GL 状态调用与它在同一 FIFO 队列中，
 * 顺序语义与单线程一致。
 * <p>
 * 仅在分离模式启用且队列已安装时使用；非分离模式由调用方直接走原路径。
 */
public final class GluSupport {

    private GluSupport() {
    }

    /**
     * 将 GLU 球体绘制（immediate 模式顶点序列）录制为一条渲染队列命令。
     * <p>
     * GLU 的曲面细分开销随命令一并移到渲染线程；球体实例由游戏侧持有且
     * 参数在录制时捕获，命令执行时实例状态不变（游戏只在初始化时配置）。
     *
     * @param sphere GLU 球体实例
     * @param radius 半径
     * @param slices 经向分段数
     * @param stacks 纬向分段数
     */
    public static void enqueueSphereDraw(final Sphere sphere, final float radius,
                                         final int slices, final int stacks) {
        BridgeSupport.enqueue(() -> sphere.draw(radius, slices, stacks));
    }
}
