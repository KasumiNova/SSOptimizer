package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StateDedup} 的相邻性去重语义验证：同类型同参数且期间本段命令列表
 * 无任何插入才跳过；类型/参数不同、段内任何命令插入（含顶点流落帧）、
 * 缓存失效（帧边界/段边界）都会使后续状态命令照常入队。
 */
class StateDedupTest {

    @Test
    void identicalAdjacentStateCommandIsSkipped() {
        StateDedup dedup = new StateDedup();
        RenderSegment segment = newSegment();

        assertFalse(dedup.shouldSkip(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0),
                "首次状态命令必须入队");
        dedup.record(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);
        assertTrue(dedup.shouldSkip(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0),
                "紧邻的相同状态命令应被跳过");
    }

    @Test
    void differentTypeOrArgumentsAreNotSkipped() {
        StateDedup dedup = new StateDedup();
        RenderSegment segment = newSegment();
        dedup.record(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);

        assertFalse(dedup.shouldSkip(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 2, 0, 0), "texture 参数不同");
        assertFalse(dedup.shouldSkip(segment, StateDedup.TYPE_BIND_TEXTURE, 1, 1, 0, 0), "target 参数不同");
        assertFalse(dedup.shouldSkip(segment, StateDedup.TYPE_ENABLE, 0, 1, 0, 0), "命令类型不同");
    }

    @Test
    void anyCommandInsertionBreaksAdjacency() {
        StateDedup dedup = new StateDedup();
        RenderSegment segment = newSegment();
        dedup.record(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);

        segment.add(() -> {
        }); // 段内任意命令插入（模拟 draw/顶点流落帧/glCallList 等一切插入）
        assertFalse(dedup.shouldSkip(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0),
                "段内命令列表有任何插入都必须打断相邻性");
    }

    @Test
    void otherSegmentInsertionDoesNotBreakAdjacency() {
        // 并行录制的段隔离语义：别的段并发插入与本段执行相邻性无关
        // （各段在回放时连续执行，拼接发生在提交前）
        StateDedup dedup = new StateDedup();
        RenderSegment mine = newSegment();
        RenderSegment others = newSegment();
        dedup.record(mine, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0);

        others.add(() -> {
        });
        assertTrue(dedup.shouldSkip(mine, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0),
                "其他段的插入不得打断本段相邻性");
    }

    @Test
    void recordAfterInterleavedCommandThenAdjacentSameIsSkipped() {
        // 语义链：A 入队 → B 入队（状态改变）→ A 入队（恢复）→ A 紧邻跳过
        StateDedup dedup = new StateDedup();
        RenderSegment segment = newSegment();

        dedup.record(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0); // A
        segment.add(() -> {
        }); // 任意命令
        dedup.record(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 2, 0, 0); // B（A→B 恢复期先入队）
        assertTrue(dedup.shouldSkip(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 2, 0, 0),
                "B 后紧邻相同的 B 应跳过");
        dedup.record(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0); // A 恢复
        assertTrue(dedup.shouldSkip(segment, StateDedup.TYPE_BIND_TEXTURE, 0, 1, 0, 0));
    }

    @Test
    void invalidateDisablesSkipping() {
        StateDedup dedup = new StateDedup();
        RenderSegment segment = newSegment();
        dedup.record(segment, StateDedup.TYPE_ENABLE, 1, 0, 0, 0);
        assertTrue(dedup.shouldSkip(segment, StateDedup.TYPE_ENABLE, 1, 0, 0, 0));

        dedup.invalidate();
        assertFalse(dedup.shouldSkip(segment, StateDedup.TYPE_ENABLE, 1, 0, 0, 0),
                "失效后（帧边界/段边界）任何状态命令都必须入队");
    }

    private static RenderSegment newSegment() {
        return new RenderSegment();
    }
}
