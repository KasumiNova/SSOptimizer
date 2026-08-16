package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CollectedBatch;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CoreInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.GlowInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.StripInstance;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EngineInstanceCollector#flatten} 的布局契约测试：
 * 命令表与三种实例记录的字段偏移、分组区段顺序必须与
 * {@code ssoptimizer_engine_batch.cpp} 的结构体保持一致。
 */
class EngineBatchFlattenTest {

    private static ByteBuffer nativeBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    @Test
    void commandTableLayoutMatchesNativeContract() {
        CollectedBatch batch = new CollectedBatch();
        batch.add(stripInstance(11));
        batch.add(stripInstance(11));
        batch.add(coreInstance(7));
        batch.add(glowInstance(9));

        ByteBuffer out = nativeBuffer(EngineInstanceCollector.flattenedBytes(batch));
        int commandCount = EngineInstanceCollector.flatten(batch, out);

        assertEquals(3, commandCount);
        // 命令 0：条带组，2 实例，数据紧随命令表之后
        assertEquals(EngineInstanceCollector.STAGE_STRIP, out.getInt(0));
        assertEquals(11, out.getInt(4));
        assertEquals(2, out.getInt(8));
        assertEquals(3 * EngineInstanceCollector.COMMAND_BYTES, out.getInt(12));
        // 命令 1：核心组，数据在条带数组之后
        int coreOffset = 3 * EngineInstanceCollector.COMMAND_BYTES
                + 2 * EngineInstanceCollector.STRIP_INSTANCE_BYTES;
        assertEquals(EngineInstanceCollector.STAGE_CORE, out.getInt(16));
        assertEquals(7, out.getInt(20));
        assertEquals(1, out.getInt(24));
        assertEquals(coreOffset, out.getInt(28));
        // 命令 2：辉光组
        assertEquals(EngineInstanceCollector.STAGE_GLOW, out.getInt(32));
        assertEquals(9, out.getInt(36));
        assertEquals(1, out.getInt(40));
        assertEquals(coreOffset + EngineInstanceCollector.CORE_INSTANCE_BYTES, out.getInt(44));
    }

    @Test
    void stripInstanceLayoutMatchesNativeContract() {
        CollectedBatch batch = new CollectedBatch();
        batch.add(stripInstance(0));

        ByteBuffer out = nativeBuffer(EngineInstanceCollector.flattenedBytes(batch));
        EngineInstanceCollector.flatten(batch, out);

        int base = EngineInstanceCollector.COMMAND_BYTES;
        assertEquals(1.0f, out.getFloat(base));
        assertEquals(2.0f, out.getFloat(base + 4));
        assertEquals(3.0f, out.getFloat(base + 8));
        assertEquals(4.0f, out.getFloat(base + 12));
        assertEquals(5.0f, out.getFloat(base + 16));
        assertEquals(6.0f, out.getFloat(base + 20));
        assertEquals(7.0f, out.getFloat(base + 24));
        assertEquals(8.0f, out.getFloat(base + 28));
        assertEquals(9.0f, out.getFloat(base + 32));
        assertEquals(10.0f, out.getFloat(base + 36));
        assertEquals(11.0f, out.getFloat(base + 40));
        assertEquals(12.0f, out.getFloat(base + 44));
        assertEquals(13.0f, out.getFloat(base + 48));
        assertEquals(14.0f, out.getFloat(base + 52));
        assertEquals((byte) 15, out.get(base + 56));
        assertEquals((byte) 16, out.get(base + 57));
        assertEquals((byte) 17, out.get(base + 58));
        assertEquals((byte) 18, out.get(base + 59));
        assertEquals((byte) 19, out.get(base + 60));
    }

    @Test
    void coreAndGlowInstanceLayoutMatchesNativeContract() {
        CollectedBatch batch = new CollectedBatch();
        batch.add(coreInstance(7));
        batch.add(glowInstance(9));

        ByteBuffer out = nativeBuffer(EngineInstanceCollector.flattenedBytes(batch));
        EngineInstanceCollector.flatten(batch, out);

        int coreBase = 2 * EngineInstanceCollector.COMMAND_BYTES;
        assertEquals(21.0f, out.getFloat(coreBase));
        assertEquals(27.0f, out.getFloat(coreBase + 24));
        assertEquals((byte) 28, out.get(coreBase + 28));
        assertEquals((byte) 31, out.get(coreBase + 31));

        int glowBase = coreBase + EngineInstanceCollector.CORE_INSTANCE_BYTES;
        assertEquals(41.0f, out.getFloat(glowBase));
        assertEquals(50.0f, out.getFloat(glowBase + 36));
        assertEquals((byte) 51, out.get(glowBase + 40));
        assertEquals((byte) 54, out.get(glowBase + 43));
    }

    @Test
    void flattenedBytesMatchesActualContent() {
        CollectedBatch batch = new CollectedBatch();
        batch.add(stripInstance(0));
        batch.add(coreInstance(7));
        batch.add(glowInstance(9));

        assertEquals(3 * EngineInstanceCollector.COMMAND_BYTES
                        + EngineInstanceCollector.STRIP_INSTANCE_BYTES
                        + EngineInstanceCollector.CORE_INSTANCE_BYTES
                        + EngineInstanceCollector.GLOW_INSTANCE_BYTES,
                EngineInstanceCollector.flattenedBytes(batch));
        assertEquals((6 + 4 + 4) * EngineInstanceCollector.VERTEX_BYTES,
                EngineInstanceCollector.expandedVertexBytes(batch));
        assertEquals((12 + 6 + 6) * 2,
                EngineInstanceCollector.expandedIndexBytes(batch));
    }

    private static StripInstance stripInstance(int textureId) {
        return new StripInstance(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f,
                9.0f, 10.0f, 11.0f, 12.0f, 13.0f, 14.0f,
                15, 16, 17, 18, 19, textureId);
    }

    private static CoreInstance coreInstance(int textureId) {
        return new CoreInstance(21.0f, 22.0f, 23.0f, 24.0f, 25.0f, 26.0f, 27.0f,
                28, 29, 30, 31, textureId);
    }

    private static GlowInstance glowInstance(int textureId) {
        return new GlowInstance(41.0f, 42.0f, 43.0f, 44.0f, 45.0f, 46.0f,
                47.0f, 48.0f, 49.0f, 50.0f,
                51, 52, 53, 54, textureId);
    }
}
