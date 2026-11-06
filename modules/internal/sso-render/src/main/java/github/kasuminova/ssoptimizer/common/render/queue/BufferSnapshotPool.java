package github.kasuminova.ssoptimizer.common.render.queue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * 录制线程与渲染线程共享的直接缓冲池，实现必须线程安全。
 * 同一快照只能归还一次；非池化缓冲可由 release 直接丢弃。
 */
public interface BufferSnapshotPool {
    ByteBuffer borrow(int bytes);

    void release(ByteBuffer buffer);

    /** 复制剩余内容且不修改源 position；快照 position=0，limit 为拷贝字节数，字节序与源一致。 */
    ByteBuffer snapshot(ByteBuffer src);

    ByteBuffer snapshot(DoubleBuffer src);

    ByteBuffer snapshot(FloatBuffer src);

    ByteBuffer snapshot(IntBuffer src);

    ByteBuffer snapshot(ShortBuffer src);

    int pooledBufferCount();

    int totalAllocations();
}
