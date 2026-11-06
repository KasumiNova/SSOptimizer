package github.kasuminova.ssoptimizer.bridge.opengl;

import org.jctools.queues.SpscArrayQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VertexStreamGrowthAccountingTest {

    @BeforeEach
    void resetBridgeStatics() {
        BridgeSupport.uninstall();
    }

    @Test
    void growthReturnsReplacedBufferToPool() {
        VertexStreamBufferPool pool = BridgeSupport.vertexStreamBufferPool();
        VertexStream stream = new VertexStream(null);

        encode(stream, 6_000);
        assertEquals(1, pool.pooledBufferCount(),
                "扩容被替换的缓冲必须归还全局池（旧实现 copyOf 丢弃在册缓冲，借出计数永久挂账）");

        BridgeSupport.releaseVertexStreamBuffer(stream.transferBuffer(), null);
        assertEquals(0, pool.droppedBufferCount(), "扩容借还全程不得丢荒，实际 " + pool.droppedBufferCount());
    }

    @Test
    void refillEvictsStaleStackEntries() {
        SpscArrayQueue<byte[]> inbox = new SpscArrayQueue<>(VertexStreamBufferPool.RETURN_INBOX_CAPACITY);
        VertexStream stream = new VertexStream(inbox);
        VertexStreamBufferPool pool = BridgeSupport.vertexStreamBufferPool();

        for (int cycle = 0; cycle < 200; cycle++) {
            List<byte[]> frame = new ArrayList<>(65);
            for (int i = 0; i < 64; i++) {
                frame.add(recordBatch(stream, 130_000));
            }
            frame.add(recordBatch(stream, 260_000));
            for (byte[] data : frame) {
                BridgeSupport.releaseVertexStreamBuffer(data, inbox);
            }
        }

        assertEquals(0, pool.droppedBufferCount(),
                "补货路径不得卡死（栈死缓冲占满 → 借出必新建、归还超限丢荒），实际丢荒 "
                        + pool.droppedBufferCount());
        assertTrue(pool.pooledBufferCount() <= VertexStreamBufferPool.RETENTION_SLACK + 160,
                "保留量必须收敛于真实在途水平，实际 " + pool.pooledBufferCount());
    }

    private static byte[] recordBatch(VertexStream stream, int bytes) {
        encode(stream, bytes);
        return stream.transferBuffer();
    }

    private static void encode(VertexStream stream, int bytes) {
        final int ops = bytes / 25;
        stream.begin(7);
        for (int i = 0; i < ops; i++) {
            stream.vertex3d(i, i, i);
        }
        stream.end();
    }
}
