package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * aux 命令游程的 GL 状态围栏：渲染线程进入一段连续的
 * {@link AuxOriginCommand} 前快照游戏侧 GL 状态（{@link #enter()}），
 * 离开该游程后恢复（{@link #exit()}），把 aux 线程（BoxUtil 后台线程等）
 * 的状态改写隔离在游程内部，避免污染紧随其后的游戏渲染命令。
 * <p>
 * 契约：只在渲染线程由执行循环调用，严格单层配对（enter 后必先 exit 才允许
 * 再次 enter）——实现可依赖此契约复用内部快照缓冲，无需支持嵌套。
 * <p>
 * 本接口位于 common 层以保持队列实现与 LWJGL 解耦；真实实现
 * （{@code bridge.opengl.GlStateFence}）由装配侧在安装队列时一并注入，
 * 默认 {@link #NOOP} 供无 GL 上下文的测试环境使用。
 */
public interface AuxRunFence {
    /** 空实现：无 GL 上下文的测试环境默认值。 */
    AuxRunFence NOOP = new AuxRunFence() {
        @Override
        public void enter() {
        }

        @Override
        public void exit() {
        }
    };

    /** 进入 aux 游程：快照当前游戏侧 GL 状态。 */
    void enter();

    /** 离开 aux 游程：恢复 {@link #enter()} 快照的 GL 状态。 */
    void exit();
}
