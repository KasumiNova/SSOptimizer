package github.kasuminova.ssoptimizer.common.render.queue;

/** 可由任意线程完成的一次性同步点；未完成时等待侧悬挂帧而不阻塞渲染线程。 */
public interface FrameFence {
    /** 幂等地标记 fence 已完成。 */
    void signal();

    boolean isSignaled();
}
