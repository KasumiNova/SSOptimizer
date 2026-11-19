package github.kasuminova.ssoptimizer.common.render.engine;

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

class EngineNativeRenderCommandTest {

    @Test
    void ofWritesCommandTableInNativeOrder() {
        CollectedBatch batch = new CollectedBatch();
        batch.add(new StripInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                15, 16, 17, 18, 19, 111));
        batch.add(new CoreInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 222));
        batch.add(new GlowInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 333));

        ByteBuffer encoded = ByteBuffer.allocate(EngineInstanceCollector.flattenedBytes(batch)).order(ByteOrder.nativeOrder());
        assertEquals(3, EngineInstanceCollector.flatten(batch, encoded));
        int header = 3 * COMMAND_BYTES;
        assertEquals(STAGE_STRIP, encoded.getInt(0));
        assertEquals(111, encoded.getInt(4));
        assertEquals(1, encoded.getInt(8));
        assertEquals(header, encoded.getInt(12));
        assertEquals(STAGE_CORE, encoded.getInt(COMMAND_BYTES));
        assertEquals(222, encoded.getInt(COMMAND_BYTES + 4));
        assertEquals(header + STRIP_INSTANCE_BYTES, encoded.getInt(COMMAND_BYTES + 12));
        assertEquals(STAGE_GLOW, encoded.getInt(COMMAND_BYTES * 2));
        assertEquals(333, encoded.getInt(COMMAND_BYTES * 2 + 4));
        assertEquals(header + STRIP_INSTANCE_BYTES + CORE_INSTANCE_BYTES,
                encoded.getInt(COMMAND_BYTES * 2 + 12));
    }
}
