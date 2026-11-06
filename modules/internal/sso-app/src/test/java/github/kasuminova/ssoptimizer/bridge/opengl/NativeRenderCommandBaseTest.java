package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NativeRenderCommandBaseTest {

    @Test
    void executeInvokesNativeBodyAndReleasesEncodedBuffer() {
        BufferSnapshotPool pool = GlDispatch.snapshotPool();
        ByteBuffer encoded = pool.borrow(64);
        int pooledAfterBorrow = pool.pooledBufferCount();
        AtomicReference<ByteBuffer> seen = new AtomicReference<>();

        new NativeRenderCommandBase(encoded) {
            @Override
            protected void executeNative(final ByteBuffer buf) {
                seen.set(buf);
            }
        }.execute();

        assertSame(encoded, seen.get(), "native 执行体必须收到录制侧编码缓冲");
        assertEquals(pooledAfterBorrow + 1, pool.pooledBufferCount(),
                "执行完编码缓冲必须归还快照池");
    }

    @Test
    void executeReleasesEncodedBufferOnNativeFailure() {
        BufferSnapshotPool pool = GlDispatch.snapshotPool();
        ByteBuffer encoded = pool.borrow(64);
        int pooledAfterBorrow = pool.pooledBufferCount();

        try {
            new NativeRenderCommandBase(encoded) {
                @Override
                protected void executeNative(final ByteBuffer buf) {
                    throw new IllegalStateException("模拟 native 执行失败");
                }
            }.execute();
        } catch (IllegalStateException expected) {
        }
        assertEquals(pooledAfterBorrow + 1, pool.pooledBufferCount(),
                "native 执行失败时编码缓冲同样必须归还快照池");
    }
}
