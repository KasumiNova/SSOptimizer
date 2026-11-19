package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.EngineNativeExecutor;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CollectedBatch;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.FrameInput;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.SlotInput;
import github.kasuminova.ssoptimizer.common.render.queue.NativeRenderCommand;

import java.util.List;

final class EngineNativeRenderCommand implements NativeRenderCommand {
    private final FrameInput frame;
    private final List<SlotInput> slots;

    /** 调用方移交独占的喷口列表，入队后禁止修改。 */
    EngineNativeRenderCommand(FrameInput frame, List<SlotInput> slots) {
        this.frame = frame;
        this.slots = slots;
    }

    boolean hasSlots() {
        return !slots.isEmpty();
    }

    CollectedBatch collect() {
        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, slots, batch);
        return batch;
    }

    @Override
    public void execute() {
        CollectedBatch batch = collect();
        EngineBatchImpl.getInstance().logBatchStats(GlCapability.Mode.VBO_BATCH, batch);
        if (!batch.isEmpty()) {
            EngineNativeExecutor.INSTANCE.execute(batch);
        }
    }
}
