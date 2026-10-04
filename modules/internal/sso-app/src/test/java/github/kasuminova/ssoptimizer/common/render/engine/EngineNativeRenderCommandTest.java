package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CollectedBatch;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CoreInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.GlowInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.StripInstance;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.COMMAND_BYTES;
import static github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CORE_INSTANCE_BYTES;
import static github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.STAGE_CORE;
import static github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.STAGE_GLOW;
import static github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.STAGE_STRIP;
import static github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.STRIP_INSTANCE_BYTES;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EngineNativeRenderCommand#of} 的编码回归验证：池化缓冲默认大端序，
 * 命令表必须按 nativeOrder 写入（C++ 侧按主机序 memcpy 解析，曾因此 SIGSEGV）。
 */
class EngineNativeRenderCommandTest {

    @Test
    void ofWritesCommandTableInNativeOrder() {
        CollectedBatch batch = new CollectedBatch();
        batch.add(new StripInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                15, 16, 17, 18, 19, 111));
        batch.add(new CoreInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 222));
        batch.add(new GlowInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 333));

        EngineNativeRenderCommand command = EngineNativeRenderCommand.of(batch);
        ByteBuffer encoded = command.encodedBufferForTest();
        try {
            assertEquals(ByteOrder.nativeOrder(), encoded.order(),
                    "命令表必须按 nativeOrder 写入（C++ 侧按主机序解析）");

            int header = 3 * COMMAND_BYTES;
            // 条带命令：实例数组紧随命令表
            assertEquals(STAGE_STRIP, encoded.getInt(0));
            assertEquals(111, encoded.getInt(4));
            assertEquals(1, encoded.getInt(8));
            assertEquals(header, encoded.getInt(12));
            // 核心命令：实例数组在条带之后
            assertEquals(STAGE_CORE, encoded.getInt(COMMAND_BYTES));
            assertEquals(222, encoded.getInt(COMMAND_BYTES + 4));
            assertEquals(header + STRIP_INSTANCE_BYTES, encoded.getInt(COMMAND_BYTES + 12));
            // 辉光命令：实例数组在核心之后
            assertEquals(STAGE_GLOW, encoded.getInt(COMMAND_BYTES * 2));
            assertEquals(333, encoded.getInt(COMMAND_BYTES * 2 + 4));
            assertEquals(header + STRIP_INSTANCE_BYTES + CORE_INSTANCE_BYTES,
                    encoded.getInt(COMMAND_BYTES * 2 + 12));
        } finally {
            // 命令未执行（无 GL 环境），编码缓冲直接归还快照池
            GlDispatch.snapshotPool().release(encoded);
        }
    }
}
