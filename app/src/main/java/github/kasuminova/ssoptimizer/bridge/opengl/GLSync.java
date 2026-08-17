package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * org.lwjgl.opengl.GLSync 的 bridge 门面：不透明句柄壳。
 * <p>
 * 动机：aux-context 折叠进单渲染线程后（见 {@link SharedDrawable} 类 javadoc），
 * BoxUtil 的 glFenceSync/glWaitSync 不再需要真实 GPU sync 对象——fence 语义退化为
 * 队列内的纯 Java 会合点。本壳只携带一个 {@link FrameFence}，录制侧（glFenceSync）
 * 与等待侧（glWaitSync，可能来自另一个生产者线程、甚至录制在信号命令之前）通过
 * 它会合，避免乱序录制造成渲染线程死锁。
 * <p>
 * ASM 重定向阶段会把 {@code org/lwjgl/opengl/GLSync} 的类型引用改写到本类
 * （含方法描述符中的返回值/参数类型），模组代码内不应保留对本壳的方法调用
 * 以外的假设——本类刻意不暴露任何真实 GL 指针语义。
 */
public final class GLSync {
    private final FrameFence fence;
    private final AtomicBoolean deleted = new AtomicBoolean();

    GLSync(FrameFence fence) {
        this.fence = fence;
    }

    /** 关联的队列内 fence（bridge 包内使用）。 */
    FrameFence fence() {
        return fence;
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
