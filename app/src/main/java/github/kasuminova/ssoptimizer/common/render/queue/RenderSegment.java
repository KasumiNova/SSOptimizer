package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.ArrayList;
import java.util.List;

/**
 * 帧内的一个有序命令段：并行录制的最小单位。
 * <p>
 * 动机：渲染遍历分区并行录制时，每个 worker 需要一个独立的命令缓冲；
 * 主线程按段登记序（而非完成序）在提交前把各段拼接成帧命令列表，
 * 回放侧因此完全无感分段的存在（见 docs/design/render-parallel-recording.md）。
 * <p>
 * <b>单写者契约</b>：同一段同一时刻只允许一个线程写入（主线程的串行段，
 * 或被编排器指派的某一个 worker）。{@link #add} 刻意不加锁——并行录制的
 * 收益正是不经过帧级临界区；编排器以帧内屏障（awaitAll）保证 worker 写完
 * 后主线程才 {@link RenderFrame#flatten()}，happens-before 由屏障建立。
 * <p>
 * {@link #commitSeq} 是段局部的提交序号：录制侧状态去重（StateDedup）的
 * 相邻性判据。段在回放时连续执行，故「相邻」只需考察本段内的插入；
 * 跨段不去重（段边界由绑定/切换路径负责失效重置）。
 */
public final class RenderSegment {
    private final List<GlCommand> commands;
    /** 段局部提交序号：每次 {@link #add} 递增。reset 不归零（去重只比较相等性）。 */
    private volatile int commitSeq;
    /**
     * 提交封存标记：帧扁平化后置位。迟到写入（编排器屏障失效的 straggler）
     * 会写进已提交帧，属必须暴露的不变量破坏——fail-fast 而非静默损坏。
     * volatile：迟到写入者与封存者之间无屏障保证，尽最大努力拦截。
     */
    private volatile boolean sealed;

    /**
     * 默认容量构造（测试/独立使用）；帧内的段由 {@link RenderFrame} 以预热容量构造。
     */
    public RenderSegment() {
        this(RenderFrame.DEFAULT_COMMAND_CAPACITY);
    }

    /**
     * @param initialCapacity 命令列表初始容量（池化复用的段不缩容，稳态零分配）
     */
    RenderSegment(final int initialCapacity) {
        this.commands = new ArrayList<>(initialCapacity);
    }

    /**
     * 追加一条命令到段尾（单写者，无同步）。
     *
     * @param command 待执行命令
     * @throws IllegalStateException 段已随帧提交封存后仍被写入
     */
    public void add(final GlCommand command) {
        if (sealed) {
            throw new IllegalStateException(
                    "[SSOptimizer] 并行段已随帧提交封存，仍有迟到写入——编排器屏障（awaitAll）未覆盖该写入者");
        }
        commands.add(command);
        commitSeq++;
    }

    /**
     * @return 段局部提交序号（自上次 {@link #add} 后未变 = 本段无任何插入，
     * StateDedup 的相邻性判据）
     */
    public int commitSeq() {
        return commitSeq;
    }

    /**
     * @return 本段命令列表（按提交顺序）；仅帧扁平化/回放路径在屏障后遍历
     */
    List<GlCommand> commands() {
        return commands;
    }

    /**
     * @return 本段当前命令数（帧命令数统计/预热/编排器负载均衡诊断用）
     */
    public int commandCount() {
        return commands.size();
    }

    /** 帧提交前调用：封存本段，拒绝一切迟到写入。 */
    void seal() {
        sealed = true;
    }

    /**
     * 帧池复用前重置：清空命令列表（不缩容，容量跨帧保留）、解除封存。
     * commitSeq 刻意不归零：去重缓存随段绑定/帧边界失效重置，序号只需保证
     * 「同段内单调」，跨帧延续不影响相等性判定。
     */
    void reset() {
        commands.clear();
        sealed = false;
    }
}
