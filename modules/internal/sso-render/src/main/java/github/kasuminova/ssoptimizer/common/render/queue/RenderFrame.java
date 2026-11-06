package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class RenderFrame {
    static final int DEFAULT_COMMAND_CAPACITY = 10;

    private final List<GlCommand>  commands;
    private final int              initialCommandCapacity;
    private final List<FrameFence> fences = new ArrayList<>();
    private CompletableFuture<Void> completion = new CompletableFuture<>();
    /** 任何命令插入都会递增，供录制侧判断状态去重是否仍然相邻。 */
    private volatile int commitSeq;
    private long sequence = -1;

    public RenderFrame() {
        this(DEFAULT_COMMAND_CAPACITY);
    }

    RenderFrame(final int initialCommandCapacity) {
        this.initialCommandCapacity = initialCommandCapacity;
        this.commands = new ArrayList<>(initialCommandCapacity);
    }

    int commandCapacity() {
        return initialCommandCapacity;
    }

    public synchronized void add(GlCommand command) {
        commands.add(command);
        commitSeq++;
    }

    /** 调用方必须持有 RenderQueueImpl.frameLock，由该锁保证互斥与可见性。 */
    void addUnlocked(GlCommand command) {
        commands.add(command);
        commitSeq++;
    }

    public int commitSeq() {
        return commitSeq;
    }

    void assignSequence(final long sequence) {
        this.sequence = sequence;
    }

    public long sequence() {
        return sequence;
    }

    public synchronized void addFence(FrameFence fence) {
        fences.add(fence);
    }

    /** 提交后仅渲染线程遍历，提交通道负责建立命令列表的可见性。 */
    List<GlCommand> commands() {
        return commands;
    }

    public List<FrameFence> fences() {
        return fences;
    }

    public synchronized int commandCount() {
        return commands.size();
    }

    /** 必须在提交帧前捕获；帧归还池后 reset 会换发 Future。 */
    synchronized CompletableFuture<Void> completionFuture() {
        return completion;
    }

    void complete() {
        completion.complete(null);
    }

    /** 失败帧必须释放登记的 fence，避免悬挂续跑任务永久自旋。 */
    void signalAllFences() {
        for (FrameFence fence : fences) {
            fence.signal();
        }
    }

    void completeExceptionally(Throwable failure) {
        completion.completeExceptionally(failure);
    }

    synchronized void reset() {
        commands.clear();
        fences.clear();
        completion = new CompletableFuture<>();
    }
}
