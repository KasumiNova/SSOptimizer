package github.kasuminova.ssoptimizer.bridge.opengl;

import org.jctools.queues.SpscArrayQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VertexStream} 编码中途扩容与 {@link VertexStreamBufferPool} 借还账目的
 * 集成回归测试（v53 实机事故：池驻留 4.6GB + 每秒约 3 万次新建/丢荒）。
 * <p>
 * 事故根因有二，本类各设回归测试：
 * <ul>
 *   <li><b>扩容账目失衡</b>：旧实现扩容用裸 {@code Arrays.copyOf}——被替换的
 *       池化缓冲借出计数永久挂账（桶 inFlight 只涨不消、峰值衰减失效 → 保留
 *       上限失控）；拷贝出的新数组日后归还是无配对递减（扩容目标桶计数负漂移
 *       → 峰值塌缩 → 超限归还丢荒）。
 *       {@link #growthReturnsReplacedBufferToPool} 直接在机制缝上断言不变量：
 *       扩容后被替换的缓冲必须归还全局池。</li>
 *   <li><b>本地栈卡死</b>：预热窗口把需求钉在峰值档时，「留栈供更小需求」的
 *       低档缓冲永远等不到需求，栈填满后补货路径整体卡死（poll→放回→新建），
 *       借出退化为每次新建、全局归还超限丢荒。
 *       {@link #refillEvictsStaleStackEntries} 用帧模型（整帧批次先录制后批量
 *       归还，inbox 溢出落全局池）验证补货逐出机制。</li>
 * </ul>
 */
class VertexStreamGrowthAccountingTest {

    @BeforeEach
    void resetBridgeStatics() {
        BridgeSupport.uninstall();
    }

    @Test
    void growthReturnsReplacedBufferToPool() {
        VertexStreamBufferPool pool = BridgeSupport.vertexStreamBufferPool();
        VertexStream stream = new VertexStream(null);

        // 编码超过初始档容量（4K），迫使编码中途扩容：旧实现 copyOf 静默丢弃
        // 被替换的在册缓冲（池里什么都看不到）；新实现必须把被替换缓冲归还
        // 全局池（此刻池内恰有且仅有这一个缓冲：流持有扩容后的新缓冲）
        encode(stream, 6_000);
        assertEquals(1, pool.pooledBufferCount(),
                "扩容被替换的缓冲必须归还全局池（旧实现 copyOf 丢弃在册缓冲，借出计数永久挂账）");

        // 移交并归还扩容后的批次：全程账目平衡，不得丢荒
        BridgeSupport.releaseVertexStreamBuffer(stream.transferBuffer(), null);
        assertEquals(0, pool.droppedBufferCount(), "扩容借还全程不得丢荒，实际 " + pool.droppedBufferCount());
    }

    @Test
    void refillEvictsStaleStackEntries() {
        SpscArrayQueue<byte[]> inbox = new SpscArrayQueue<>(VertexStreamBufferPool.RETURN_INBOX_CAPACITY);
        VertexStream stream = new VertexStream(inbox);
        VertexStreamBufferPool pool = BridgeSupport.vertexStreamBufferPool();

        // 帧模型：每周期 65 个同档批次先全部录制再批量归还（渲染线程执行完
        // 一帧 65 条批次命令：inbox 容量 32，溢出 33 个落全局池，全局桶形成
        // 真实借还流量）；周期尾的大批次扩容把升档中继缓冲灌入本线程栈——
        // 无逐出机制时栈被死缓冲填满、补货卡死（每周期新建/丢荒各 30+）
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

    /** 编码一个约 {@code bytes} 字节的批次并移交（调用方持有缓冲，模拟帧命令在飞）。 */
    private static byte[] recordBatch(VertexStream stream, int bytes) {
        encode(stream, bytes);
        return stream.transferBuffer();
    }

    /** 编码一个约 {@code bytes} 字节的批次（不移交）。 */
    private static void encode(VertexStream stream, int bytes) {
        // vertex3d 每条 25 字节（1 操作码 + 3 double）
        final int ops = bytes / 25;
        stream.begin(7);
        for (int i = 0; i < ops; i++) {
            stream.vertex3d(i, i, i);
        }
        stream.end();
    }
}
