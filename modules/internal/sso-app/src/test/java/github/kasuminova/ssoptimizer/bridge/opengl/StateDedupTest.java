package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateDedupTest {

    @Test
    void identicalAdjacentStateCommandIsSkipped() {
        StateDedup dedup = new StateDedup();
        RenderFrame frame = new RenderFrame();

        assertFalse(dedup.shouldSkip(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0),
                "首次状态命令必须入队");
        dedup.record(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);
        assertTrue(dedup.shouldSkip(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0),
                "紧邻的相同状态命令应被跳过");
    }

    @Test
    void differentTypeOrArgumentsAreNotSkipped() {
        StateDedup dedup = new StateDedup();
        RenderFrame frame = new RenderFrame();
        dedup.record(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);

        assertFalse(dedup.shouldSkip(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 2, 0, 0), "texture 参数不同");
        assertFalse(dedup.shouldSkip(frame, StateDedup.TYPE_BIND_TEXTURE, 1, 1, 0, 0), "target 参数不同");
        assertFalse(dedup.shouldSkip(frame, StateDedup.TYPE_ENABLE, 0, 1, 0, 0), "命令类型不同");
    }

    @Test
    void anyCommandInsertionBreaksAdjacency() {
        StateDedup dedup = new StateDedup();
        RenderFrame frame = new RenderFrame();
        dedup.record(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);

        frame.add(() -> {
        });
        assertFalse(dedup.shouldSkip(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0),
                "帧命令列表有任何插入都必须打断相邻性");
    }

    @Test
    void recordAfterInterleavedCommandThenAdjacentSameIsSkipped() {
        StateDedup dedup = new StateDedup();
        RenderFrame frame = new RenderFrame();

        dedup.record(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);
        frame.add(() -> {
        });
        dedup.record(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 2, 0, 0);
        assertTrue(dedup.shouldSkip(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 2, 0, 0),
                "B 后紧邻相同的 B 应跳过");
        dedup.record(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);
        assertTrue(dedup.shouldSkip(frame, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0));
    }

    @Test
    void invalidateDisablesSkipping() {
        StateDedup dedup = new StateDedup();
        RenderFrame frame = new RenderFrame();
        dedup.record(frame, StateDedup.TYPE_ENABLE, 1, 0, 0, 0);
        assertTrue(dedup.shouldSkip(frame, StateDedup.TYPE_ENABLE, 1, 0, 0, 0));

        dedup.invalidate();
        assertFalse(dedup.shouldSkip(frame, StateDedup.TYPE_ENABLE, 1, 0, 0, 0),
                "失效后（帧边界）任何状态命令都必须入队");
    }
}
