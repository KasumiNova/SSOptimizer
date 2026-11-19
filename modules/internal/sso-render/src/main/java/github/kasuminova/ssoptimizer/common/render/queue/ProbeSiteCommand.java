package github.kasuminova.ssoptimizer.common.render.queue;

public final class ProbeSiteCommand implements GlCommand {
    private final GlCommand delegate;
    private final Throwable recordingSite;

    public ProbeSiteCommand(final GlCommand delegate) {
        this.delegate = delegate;
        this.recordingSite = new Throwable("[SSOptimizer] GL 命令录制点（诊断堆栈，非异常）");
    }

    @Override
    public void execute() {
        delegate.execute();
    }

    public static GlCommand unwrap(GlCommand command) {
        while (command instanceof ProbeSiteCommand probe) {
            command = probe.delegate;
        }
        return command;
    }

    public GlCommand delegate() {
        return delegate;
    }

    public Throwable recordingSite() {
        return recordingSite;
    }
}
