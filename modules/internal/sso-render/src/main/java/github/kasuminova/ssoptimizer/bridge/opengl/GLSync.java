package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * org.lwjgl.opengl.GLSync 的 bridge 门面：不透明句柄壳。
 * <p>
 * 会合语义由 {@link FrameFence}（Java 会合点）承载：录制侧（glFenceSync）与
 * 等待侧（glWaitSync/glClientWaitSync，可能来自另一个生产者线程）通过它会合；
 * 队列内等待未会合时帧悬挂续跑而非阻塞（见 WaitFenceCommand），避免乱序/滞后
 * 录制造成渲染线程死锁。
 * <p>
 * SharedDrawable 解折叠后 fence 恢复真实 GPU 序语义：句柄额外携带真实
 * {@code org.lwjgl.opengl.GLSync}（{@link #realSync()}）。附着时机按产出线程
 * 分两种：
 * <ul>
 *   <li>aux 原生线程产出：glFenceSync 原生直执，创建即附着（latch 预 signal）；</li>
 *   <li>主线程产出：真实 sync 由渲染线程执行 SignalFenceCommand 时在命令流
 *       序列点创建并附着，附着 happens-before latch signal。</li>
 * </ul>
 * <p>
 * ASM 重定向阶段会把 {@code org/lwjgl/opengl/GLSync} 的类型引用改写到本类
 * （含方法描述符中的返回值/参数类型），模组代码内不应保留对本壳的方法调用
 * 以外的假设——本类刻意不暴露任何真实 GL 指针语义。
 */
public final class GLSync {
    private final FrameFence fence;
    private final AtomicBoolean deleted = new AtomicBoolean();
    /**
     * 真实 GL sync 句柄（不透明 Object，见 {@link RealSyncOps}）：主产 fence 在渲染
     * 线程序列点附着，aux 产 fence 创建即附着。
     */
    private volatile Object real;

    GLSync(FrameFence fence) {
        this.fence = fence;
    }

    /** aux 原生线程产出形态：真实 sync 已存在，latch 语义由调用方预 signal。 */
    GLSync(FrameFence fence, Object real) {
        this.fence = fence;
        this.real = real;
    }

    /** 关联的队列内 fence（bridge 包内使用）。 */
    FrameFence fence() {
        return fence;
    }

    /** 真实 GL sync 句柄（可为 null：主产 fence 的信号命令尚未执行到时）。 */
    Object realSync() {
        return real;
    }

    /** 渲染线程信号命令体内的真实 sync 附着（必须在 fence.signal() 之前调用）。 */
    void attachReal(Object real) {
        this.real = real;
    }

    /** glDeleteSync 语义：标记句柄失效（幂等）。 */
    void markDeleted() {
        deleted.set(true);
    }

    /**
     * @return 句柄是否已被 glDeleteSync 标记失效（诊断/测试用）
     */
    public boolean isDeleted() {
        return deleted.get();
    }
}
