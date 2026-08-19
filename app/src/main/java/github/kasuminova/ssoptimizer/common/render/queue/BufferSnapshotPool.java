package github.kasuminova.ssoptimizer.common.render.queue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * GL 命令录制用的直接缓冲快照池（2 的幂分级），FR BufferPool 的等价物。
 * <p>
 * 动机：渲染/逻辑线程分离后，携带 {@code ByteBuffer}/{@code FloatBuffer} 等参数的
 * GL 调用（glTexImage2D/glBufferData/glVertexPointer 等）在录制时刻与执行时刻之间
 * 隔着一个帧交换窗口，调用方完全可能在命令执行前改写或复用同一个 buffer。因此
 * 录制时必须把 buffer 的剩余内容深拷贝进一个归 bridge 所有的直接缓冲快照，
 * 命令执行后再归还，避免每条命令都新建直接缓冲的分配开销。
 * <p>
 * 使用契约：
 * <ul>
 *   <li>{@link #snapshot} 系列返回的快照缓冲 position=0、limit=已拷贝字节数，
 *       字节序与源 buffer 一致；</li>
 *   <li>快照用毕必须 {@link #release} 归还（命令体的 finally 块）；同一快照
 *       只允许归还一次，重复归还会把同一实例多次放回池造成别名污染；</li>
 *   <li>超过池化上限的快照走非池化分配，{@link #release} 对其是安全空操作，
 *       调用方无需区分。</li>
 * </ul>
 * 线程安全：录制发生在主线程与 aux-context 生产者线程，归还发生在渲染线程，
 * 实现必须全程线程安全。
 */
public interface BufferSnapshotPool {
    /**
     * 借出一个容量不小于 {@code bytes} 的直接缓冲（position=0，limit=capacity）。
     *
     * @param bytes 需要的字节数（小于等于 0 时按 1 处理）
     * @return 池化或新分配的直接缓冲
     */
    ByteBuffer borrow(int bytes);

    /**
     * 归还 {@link #borrow}/{@link #snapshot} 得到的缓冲。非池化缓冲（超过池化
     * 上限的大块）在此直接丢弃等待 GC。
     *
     * @param buffer 待归还缓冲
     */
    void release(ByteBuffer buffer);

    /**
     * 深拷贝 {@code src} 的剩余内容（position 到 limit）到池内快照缓冲。
     *
     * @param src 源缓冲（不会被修改 position）
     * @return 快照：position=0，limit=拷贝字节数，字节序与源一致
     */
    ByteBuffer snapshot(ByteBuffer src);

    /** {@link #snapshot(ByteBuffer)} 的 {@link DoubleBuffer} 版本。 */
    ByteBuffer snapshot(DoubleBuffer src);

    /** {@link #snapshot(ByteBuffer)} 的 {@link FloatBuffer} 版本。 */
    ByteBuffer snapshot(FloatBuffer src);

    /** {@link #snapshot(ByteBuffer)} 的 {@link IntBuffer} 版本。 */
    ByteBuffer snapshot(IntBuffer src);

    /** {@link #snapshot(ByteBuffer)} 的 {@link ShortBuffer} 版本。 */
    ByteBuffer snapshot(ShortBuffer src);

    /**
     * @return 当前空闲在池中的缓冲总数（诊断/测试用）
     */
    int pooledBufferCount();

    /**
     * @return 累计新建直接缓冲的次数（诊断/测试用；复用池内缓冲不计数）
     */
    int totalAllocations();
}
