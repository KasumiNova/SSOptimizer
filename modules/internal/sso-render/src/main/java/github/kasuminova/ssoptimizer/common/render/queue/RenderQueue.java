package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.concurrent.Callable;

/** 多录制线程提交命令，单渲染线程按序执行；swapFramesAndSync 只等待上一帧。 */
public interface RenderQueue {
    /** 仅限主录制线程；其他生产者必须调用 submit。 */
    RenderFrame currentFrame();

    /** 线程安全。正常返回后由队列持有命令；抛出异常时仍由调用方持有。跨线程顺序由 FrameFence 协调。 */
    void submit(GlCommand command);

    void swapFrames();

    /** 上一帧失败时向主线程抛出 IllegalStateException，cause 为原始异常。 */
    void swapFramesAndSync();

    /** 阻塞等待渲染线程结果；渲染线程内调用时直接执行以避免自死锁。 */
    <T> T get(Callable<T> getter);

    /** 语义同 get，但不计入 StallDetector。 */
    default <T> T getUncounted(Callable<T> getter) {
        return get(getter);
    }

    void wait(Runnable task);

    default void waitUncounted(Runnable task) {
        getUncounted(() -> {
            task.run();
            return null;
        });
    }

    boolean isRenderThread();
}
