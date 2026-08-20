package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一帧的 GL 命令缓冲。
 * <p>
 * 主线程（或 aux-context 生产者线程）在录制期向当前帧追加命令与 fence 登记；
 * {@link RenderQueue#swapFrames()} 后本帧被提交给渲染线程按序执行，执行完成后
 * 完成帧 Future 并归还 {@link FramePool} 复用。
 * <p>
 * 实现说明：命令存储用 ArrayList 保持简单；当前每次 GL 调用都会分配一个捕获
 * lambda 命令对象，后续若 profiling 显示分配压力显著，可在不改变本类语义的前提
 * 下引入命令对象池（FR 的 Frame 同样从简单列表起步）。
 */
public final class RenderFrame {
    /** 命令列表的默认初始容量：新建帧的容量下限（与 JDK {@link ArrayList} 无参构造一致）。 */
    static final int DEFAULT_COMMAND_CAPACITY = 10;

    private final List<GlCommand>  commands;
    private final int              initialCommandCapacity;
    private final List<FrameFence> fences = new ArrayList<>();
    /** 帧执行完成信号；{@link #reset()} 换发新实例，已提交帧的旧 Future 结果不受影响。 */
    private CompletableFuture<Void> completion = new CompletableFuture<>();
    /**
     * 本帧命令列表的提交序号：每次 {@link #add} 递增（多生产者线程安全的
     * 单调计数器）。录制侧状态命令去重（{@code StateDedup}）以此为「相邻性」
     * 判据——自上次状态命令入队以来序号未变，说明帧列表没有任何插入
     * （含 aux 生产者线程的并发提交、顶点流落帧、glCallList 等一切命令），
     * 去重跳过才是安全的；序号变化即视为状态可能已被其他路径改变。
     */
    private volatile int commitSeq;

    /**
     * 默认容量构造：命令列表按 {@link #DEFAULT_COMMAND_CAPACITY} 起步。
     */
    public RenderFrame() {
        this(DEFAULT_COMMAND_CAPACITY);
    }

    /**
     * 按预热容量构造：新建帧（池空时 {@link FramePool#acquire()} 的产物）的命令列表
     * 初始容量取 {@link FramePool} 记录的近 N 帧命令数峰值，避免每帧从默认容量
     * 渐进扩容（v49 profile：{@code ArrayList.grow} 2,728 样本的主要来源）。
     * 池化复用帧不走本构造：{@link #reset()} 的 clear 不缩容，容量跨帧保留。
     *
     * @param initialCommandCapacity 命令列表初始容量（实际容量可随超峰命令增长）
     */
    RenderFrame(final int initialCommandCapacity) {
        this.initialCommandCapacity = initialCommandCapacity;
        this.commands = new ArrayList<>(initialCommandCapacity);
    }

    /**
     * @return 本帧命令列表的初始容量（构造时预定的预热基线；列表实际容量可能随
     * 超峰命令增长，测试用）
     */
    int commandCapacity() {
        return initialCommandCapacity;
    }

    /**
     * 追加一条命令到帧尾。synchronized 保证主线程直接录制与 aux-context 生产者
     * 线程经 {@link RenderQueue#submit(GlCommand)} 并发录制时列表完整；
     * 跨线程的命令先后顺序本身不保证（由 fence 协调可见性）。
     *
     * @param command 待执行命令
     */
    public synchronized void add(GlCommand command) {
        commands.add(command);
        commitSeq++;
    }

    /**
     * 队列提交路径的无锁追加（调用方必须已持有 {@code RenderQueueImpl} 的
     * frameLock）：与 {@link #add(GlCommand)} 的区别仅在于不再进出帧自身
     * 监视器——提交路径的互斥与可见性已由 frameLock 提供，省去录制热路径上
     * 每命令一次的嵌套监视器开销。帧归还池后不可能再被本方法触达（提交方
     * 只能经 frameLock 拿到当前帧）。
     *
     * @param command 待执行命令
     */
    void addUnlocked(GlCommand command) {
        commands.add(command);
        commitSeq++;
    }

    /**
     * @return 本帧当前提交序号（自上次 {@link #add} 后未变 = 命令列表无任何插入）
     */
    public int commitSeq() {
        return commitSeq;
    }

    /**
     * 登记本帧录制期间产生的 fence（随 {@link #reset()} 一并清理，仅作生命周期簿记；
     * fence 的 signal/await 语义与帧无关）。
     *
     * @param fence 本帧产生的 fence
     */
    public synchronized void addFence(FrameFence fence) {
        fences.add(fence);
    }

    /**
     * 渲染线程在帧提交后独占遍历命令列表（提交后生产者录制的是下一帧，
     * 经提交通道的 happens-before 保证可见），无需同步。
     *
     * @return 本帧命令列表（按提交顺序）
     */
    List<GlCommand> commands() {
        return commands;
    }

    /**
     * @return 本帧登记的 fence 列表（诊断/测试用）
     */
    public List<FrameFence> fences() {
        return fences;
    }

    /**
     * @return 当前命令数（诊断/测试用）
     */
    public synchronized int commandCount() {
        return commands.size();
    }

    /**
     * 取本帧当前执行周期的完成 Future。主线程必须在「提交帧到渲染队列之前」
     * 捕获该引用：帧执行完归还池后 {@link #reset()} 会换发新 Future，事后再读本
     * 字段拿到的是下一周期的实例，等它会永远阻塞。
     *
     * @return 本帧当前周期的完成 Future
     */
    synchronized CompletableFuture<Void> completionFuture() {
        return completion;
    }

    /** 渲染线程用：本帧全部命令执行成功。 */
    void complete() {
        completion.complete(null);
    }

    /**
     * 帧执行失败兜底：强制完成本帧登记的全部 fence。失败帧的剩余命令（含
     * {@link SignalFenceCommand}）会被丢弃，若不释放 fence，等待它的悬挂续跑
     * 任务将永久自旋堵塞提交队列。已 signal 的 fence 重复 signal 幂等无害。
     */
    void signalAllFences() {
        for (FrameFence fence : fences) {
            fence.signal();
        }
    }

    /** 渲染线程用：本帧命令执行失败，异常随 Future 传播到主线程。 */
    void completeExceptionally(Throwable failure) {
        completion.completeExceptionally(failure);
    }

    /**
     * 池化复用前重置：清空命令与 fence 登记，换发新的完成 Future。
     * 旧 Future 对象不被复用，已提交帧的完成/异常结果对等待方仍然有效。
     */
    synchronized void reset() {
        commands.clear();
        fences.clear();
        completion = new CompletableFuture<>();
    }
}
