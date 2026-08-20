package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 命令级 GL 错误探针的录制点包装命令——仅诊断设施，仅在
 * {@link RenderQueueImpl#GL_ERROR_PROBE_PROPERTY} 为 {@code command} 时由
 * BridgeSupport 录制侧包装产生。
 * <p>
 * 动机：bridge GL 镜像的绝大多数命令体是匿名 lambda（类名形如
 * {@code GL11$$Lambda/0x...}），逐命令探针定位到出错命令后仍无法知道它是
 * 哪个 GL 调用、从哪条代码路径录制。包装命令在录制时刻捕获一份诊断堆栈，
 * 探针在该命令之后排空到 GL 错误时一并输出，直接指向录制点。
 * <p>
 * 生产路径零开销：探针关闭时不产生任何包装实例。
 */
public final class ProbeSiteCommand implements GlCommand {
    private final GlCommand delegate;
    /** 录制点诊断堆栈（非真实异常，仅作堆栈载体）。 */
    private final Throwable recordingSite;

    /**
     * @param delegate 被包装的真实命令
     */
    public ProbeSiteCommand(final GlCommand delegate) {
        this.delegate = delegate;
        this.recordingSite = new Throwable("[SSOptimizer] GL 命令录制点（诊断堆栈，非异常）");
    }

    @Override
    public void execute() {
        delegate.execute();
    }

    /** 被包装的真实命令（日志展示其类型名）。 */
    public GlCommand delegate() {
        return delegate;
    }

    /** 录制点诊断堆栈。 */
    public Throwable recordingSite() {
        return recordingSite;
    }
}
