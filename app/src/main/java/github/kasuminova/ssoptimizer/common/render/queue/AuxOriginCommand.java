package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * aux-context 生产者线程（BoxUtil 后台线程、aitweaks 等模组的自有线程）提交
 * 命令的包装标记。
 * <p>
 * 动机：渲染/逻辑线程分离把所有线程的 GL 调用折叠进唯一渲染线程上下文执行
 * （SharedDrawable 折叠模型）。aux 线程的命令与游戏渲染命令在帧列表中交错，
 * 其改写的 GL 状态（纹理绑定、着色器、矩阵等）若不隔离，会污染紧随其后的
 * 游戏渲染命令——文本渲染腐坏（满屏纯色方块）的根因即此。本标记让渲染线程
 * 的执行循环（{@link RenderQueueImpl} 的游程状态机）能识别一段连续的 aux
 * 命令，用 {@link AuxRunFence} 在进入前快照游戏 GL 状态、离开后恢复。
 * <p>
 * 包装发生在录制侧（bridge 入队时）；{@link #execute()} 只透传给被包装命令，
 * 围栏的 enter/exit 由执行循环在命令边界完成——单个 aux 命令自身也在围栏
 * 保护内执行（enter 先于其执行，exit 在其后首个非 aux 命令或帧末发生）。
 *
 * @param delegate 被包装的原始命令
 */
public record AuxOriginCommand(GlCommand delegate) implements GlCommand {
    @Override
    public void execute() {
        delegate.execute();
    }
}
