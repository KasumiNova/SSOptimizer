package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一帧的 GL 命令缓冲（分段模型）。
 * <p>
 * 帧 = 一组按登记序排列的{@link RenderSegment}：主线程的串行录制写入
 * 「当前串行段」（{@link #add} 的语义与单列表时代一致）；并行录制期由编排器
 * {@link #reserveSegments(int)} 预定 N 个段指派给 worker，屏障后经
 * {@link #openNextSerialSegment()} 开启后续串行段。{@link RenderQueue#swapFrames()}
 * 提交前 {@link #flatten()} 按段登记序把各段拼接成帧命令列表——回放侧
 * （FrameTask/悬挂续跑/fence）面对的仍是一个有序列表，完全无感分段。
 * <p>
 * 实现说明：命令存储用 ArrayList 保持简单；当前每次 GL 调用都会分配一个捕获
 * lambda 命令对象，后续若 profiling 显示分配压力显著，可在不改变本类语义的前提
 * 下引入命令对象池（FR 的 Frame 同样从简单列表起步）。
 */
public final class RenderFrame {
    /** 命令列表的默认初始容量：新建帧的容量下限（与 JDK {@link ArrayList} 无参构造一致）。 */
    static final int DEFAULT_COMMAND_CAPACITY = 10;

    /** 按登记序排列的段（索引 0 为帧的默认串行段）；拼接顺序即本列表顺序。 */
    private final List<RenderSegment> segments = new ArrayList<>();
    /**
     * 段对象备件池：{@link #reset()} 时回收本帧使用过的段对象供后续
     * {@link #reserveSegments} 复用——段内 ArrayList 不缩容，稳态零分配
     * （段缓冲预热即此复用链，随帧池跨帧生效）。
     */
    private final List<RenderSegment> spareSegments = new ArrayList<>();
    private final int              initialCommandCapacity;
    /** {@link #add} 的当前追加目标（主线程串行段）。volatile：aux 生产者可并发读。 */
    private volatile RenderSegment serialSegment;
    /** 扁平化后的帧命令列表（{@link #flatten()} 产物；单段帧直指该段列表，零拷贝）。 */
    private List<GlCommand> flattened;
    private final List<FrameFence> fences = new ArrayList<>();
    /** 帧执行完成信号；{@link #reset()} 换发新实例，已提交帧的旧 Future 结果不受影响。 */
    private CompletableFuture<Void> completion = new CompletableFuture<>();
    /**
     * 串行录制路径的提交序号：每次 {@link #add} 递增（多生产者线程安全的
     * 单调计数器）。并行段的写入不经 {@link #add}（各段有自己的局部序号，
     * 见 {@link RenderSegment#commitSeq()}），本计数只反映串行段插入。
     */
    private volatile int commitSeq;

    /**
     * 默认容量构造：命令列表按 {@link #DEFAULT_COMMAND_CAPACITY} 起步。
     */
    public RenderFrame() {
        this(DEFAULT_COMMAND_CAPACITY);
    }

    /**
     * 按预热容量构造：新建帧（池空时 {@link FramePool#acquire()} 的产物）的默认串行段
     * 命令列表初始容量取 {@link FramePool} 记录的近 N 帧命令数峰值，避免每帧从默认容量
     * 渐进扩容（v49 profile：{@code ArrayList.grow} 2,728 样本的主要来源）。
     * 池化复用帧不走本构造：{@link #reset()} 的 clear 不缩容，容量跨帧保留。
     *
     * @param initialCommandCapacity 默认串行段命令列表初始容量
     */
    RenderFrame(final int initialCommandCapacity) {
        this.initialCommandCapacity = initialCommandCapacity;
        RenderSegment initial = new RenderSegment(initialCommandCapacity);
        segments.add(initial);
        serialSegment = initial;
    }

    /**
     * @return 本帧默认串行段的初始容量（构造时预定的预热基线；实际容量可随
     * 超峰命令增长，测试用）
     */
    int commandCapacity() {
        return initialCommandCapacity;
    }

    /**
     * 追加一条命令到当前串行段。synchronized 保证主线程直接录制与 aux-context
     * 生产者线程经 {@link RenderQueue#submit(GlCommand)} 并发录制时列表完整；
     * 跨线程的命令先后顺序本身不保证（由 fence 协调可见性）。
     *
     * @param command 待执行命令
     */
    public synchronized void add(GlCommand command) {
        serialSegment.add(command);
        commitSeq++;
    }

    /**
     * @return 串行录制路径的提交序号（自上次 {@link #add} 后未变 = 串行段无插入）
     */
    public int commitSeq() {
        return commitSeq;
    }

    /**
     * @return 当前串行段（{@link #add} 的追加目标；录制侧状态去重的相邻性
     * 判据来源——串行路径全部写入都落在此段）
     */
    public RenderSegment serialSegment() {
        return serialSegment;
    }

    /**
     * 预定 n 个并行段（仅主线程/编排器调用，且在 worker 任务分发之前）。
     * 段按登记序追加进帧，拼接顺序与 worker 完成先后无关。
     * <p>
     * <b>不变量</b>：被指派段的 worker 必须在本帧下一次 swap 前全部 join
     * （编排器以帧内屏障保证）；swap 时 {@link #flatten()} 会封存全部段，
     * 迟到写入触发 fail-fast。
     *
     * @param n 段数
     * @return 首个段的索引（worker i 使用 {@code segment(base + i)}）
     */
    public synchronized int reserveSegments(final int n) {
        if (n < 1) {
            throw new IllegalArgumentException("segment count must be >= 1, got " + n);
        }
        int base = segments.size();
        for (int i = 0; i < n; i++) {
            segments.add(takeSpareSegment());
        }
        return base;
    }

    /**
     * 取指定登记序的段（编排器把段指派给 worker 用）。
     *
     * @param index {@link #reserveSegments} 返回的段索引
     */
    public RenderSegment segment(final int index) {
        return segments.get(index);
    }

    /**
     * 开启下一个串行段（并行区屏障后，主线程后续录制写入新段，
     * 拼接序自然落在刚结束的并行段之后）。
     *
     * @return 新的当前串行段
     */
    public synchronized RenderSegment openNextSerialSegment() {
        RenderSegment next = takeSpareSegment();
        segments.add(next);
        serialSegment = next;
        return next;
    }

    /** 取一个段对象：备件池优先，空则新建。 */
    private RenderSegment takeSpareSegment() {
        int spareCount = spareSegments.size();
        if (spareCount > 0) {
            return spareSegments.remove(spareCount - 1);
        }
        return new RenderSegment(DEFAULT_COMMAND_CAPACITY);
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
     * 扁平化：提交前按段登记序拼接出帧命令列表并封存全部段。
     * 单段帧直指该段命令列表（零拷贝快速路径，非并行帧零开销）。
     * 仅主线程在编排器屏障后、提交进渲染队列前调用（happens-before 由屏障建立）；
     * 幂等，重复调用不产生变化。
     */
    public synchronized void flatten() {
        if (flattened != null) {
            return;
        }
        for (RenderSegment segment : segments) {
            segment.seal();
        }
        if (segments.size() == 1) {
            flattened = segments.get(0).commands();
            return;
        }
        int total = 0;
        for (RenderSegment segment : segments) {
            total += segment.commandCount();
        }
        List<GlCommand> joined = new ArrayList<>(total);
        for (RenderSegment segment : segments) {
            joined.addAll(segment.commands());
        }
        flattened = joined;
    }

    /**
     * 渲染线程在帧提交后独占遍历命令列表（提交后生产者录制的是下一帧，
     * 经提交通道的 happens-before 保证可见），无需同步。
     *
     * @return 本帧命令列表（按段登记序拼接后的提交顺序）
     */
    List<GlCommand> commands() {
        List<GlCommand> flat = flattened;
        if (flat == null) {
            // 测试/诊断路径的惰性扁平化；生产路径在提交前已 flatten
            flatten();
            flat = flattened;
        }
        return flat;
    }

    /**
     * @return 本帧登记的 fence 列表（诊断/测试用）
     */
    public List<FrameFence> fences() {
        return fences;
    }

    /**
     * @return 当前命令数（各段合计；诊断/测试/帧池预热统计用）
     */
    public synchronized int commandCount() {
        int total = 0;
        for (RenderSegment segment : segments) {
            total += segment.commandCount();
        }
        return total;
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
     * 池化复用前重置：清空命令与 fence 登记，换发新的完成 Future，
     * 段结构回到「单一默认串行段」（其余段重置后收入备件池复用，未动用的
     * 备件保留在原位跨帧沿用）。
     * 旧 Future 对象不被复用，已提交帧的完成/异常结果对等待方仍然有效。
     */
    synchronized void reset() {
        for (int i = 1; i < segments.size(); i++) {
            RenderSegment segment = segments.get(i);
            segment.reset();
            spareSegments.add(segment);
        }
        RenderSegment first = segments.get(0);
        first.reset();
        segments.clear();
        segments.add(first);
        serialSegment = first;
        flattened = null;
        fences.clear();
        completion = new CompletableFuture<>();
    }
}
