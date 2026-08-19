package github.kasuminova.ssoptimizer.bridge.opengl;

import com.fs.graphics.util.GLListManager.GLListToken;
import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DisplayListGuard} 的语义验证：簿记节奏与原版 GLListManager 一致
 * （帧边界驱逐、失效回池、嵌套抛错、suspend 退化），并行化新增面
 * （段内 stash 耗尽退化、fresh-token 护栏）逐条覆盖。
 * <p>
 * display list 命令体（glNewList/glEndList/glCallList）经 bridge 录制进
 * FakeRenderQueue，无 GL 上下文环境只验证录制行为与簿记状态机。
 */
class DisplayListGuardTest {
    /** stash 批量补货桩：返回 [1000, 1000+512) 的连续 id 段。 */
    private static int[] stubBatch() {
        int[] batch = new int[BridgeSupport.LIST_ID_STASH_BATCH];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = 1000 + i;
        }
        return batch;
    }

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL11.install(queue);
        queue.getHandler = callable -> stubBatch();
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
    }

    @Test
    void beginEndCallHappyPath() {
        GLListToken token = DisplayListGuard.beginList();
        assertNotNull(token);
        assertTrue(DisplayListGuard.isBuildingList(), "beginList 后线程处于编译中");
        DisplayListGuard.endList();
        assertFalse(DisplayListGuard.isBuildingList(), "endList 后编译标志清除");
        assertEquals(2, queue.recorded.size(), "glNewList + glEndList 各录制一条");

        assertTrue(DisplayListGuard.callList(token));
        assertTrue(DisplayListGuard.callList(token));
        assertEquals(4, queue.recorded.size(), "两次 callList 各录制一条 glCallList");
        assertEquals(1, queue.uncountedGetCallCount, "id 来自一次 stash 批量预生成");
    }

    @Test
    void nestedBeginListThrows() {
        DisplayListGuard.beginList();
        RuntimeException ex = assertThrows(RuntimeException.class, DisplayListGuard::beginList);
        assertEquals("Can't create nested lists using GLListManager", ex.getMessage());
        DisplayListGuard.endList();
    }

    @Test
    void buildingFlagIsThreadLocal() throws Exception {
        DisplayListGuard.beginList();
        boolean[] otherThread = new boolean[1];
        Thread worker = new Thread(() -> otherThread[0] = DisplayListGuard.isBuildingList());
        worker.start();
        worker.join();
        assertFalse(otherThread[0], "buildingList 是线程语义，其他线程不受编译中影响");
        DisplayListGuard.endList();
    }

    @Test
    void suspendDisablesEverything() {
        DisplayListGuard.setSuspend(true);
        assertNull(DisplayListGuard.beginList(), "suspend 下 beginList 返回 null");
        DisplayListGuard.endList();
        assertEquals(0, queue.recorded.size(), "suspend 下 endList 不录制 glEndList");
        DisplayListGuard.setSuspend(false);

        GLListToken token = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        DisplayListGuard.setSuspend(true);
        assertFalse(DisplayListGuard.callList(token), "suspend 下 callList 按未命中处理");
        DisplayListGuard.setSuspend(false);
    }

    @Test
    void nextFrameEvictsUntouchedTokenAndReusesId() {
        GLListToken token = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        assertTrue(DisplayListGuard.callList(token));

        // 下一帧未触达 → 驱逐；再下一帧 callList 按未命中
        DisplayListGuard.nextFrame();
        DisplayListGuard.nextFrame();
        assertFalse(DisplayListGuard.callList(token), "被驱逐的 token 调用按未命中处理");

        int recordedBefore = queue.recorded.size();
        GLListToken rebuilt = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        assertNotNull(rebuilt);
        assertEquals(1, queue.uncountedGetCallCount, "驱逐回收的 id 复用，不新增批量预生成");
        assertTrue(queue.recorded.size() > recordedBefore, "重建录制新的 glNewList/glEndList");
    }

    @Test
    void invalidateRecyclesId() {
        GLListToken token = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        DisplayListGuard.invalidateList(token);
        assertFalse(DisplayListGuard.callList(token), "失效后 callList 按未命中处理");
        DisplayListGuard.invalidateList(null);
        DisplayListGuard.invalidateList(token);
        GLListToken rebuilt = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        assertNotNull(rebuilt);
        assertEquals(1, queue.uncountedGetCallCount, "失效回收的 id 复用，不新增批量预生成");
    }

    @Test
    void beginListInBoundSegmentWithEmptyStashReturnsNull() {
        // 新安装队列 stash 为空：段内禁止阻塞补货，按 suspend 等价语义退化
        RenderSegment segment = new RenderSegment();
        BridgeSupport.bindSegment(segment);
        try {
            assertNull(DisplayListGuard.beginList(), "段内 stash 耗尽返回 null（调用方直接渲染回退）");
            assertFalse(DisplayListGuard.isBuildingList(), "退化路径不置编译标志");
        } finally {
            BridgeSupport.unbindSegment();
        }
        assertEquals(0, queue.uncountedGetCallCount, "段内不触发阻塞补货");
    }

    @Test
    void freshTokenGuardRejectsCrossSegmentCall() {
        // 段外暖池：stash 由主线程批量预生成就位（段内禁止阻塞补货）
        GLListToken warm = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        // 段 A 新建 token：段内调用放行（段内重放序保持），跨段调用拦截
        RenderSegment segmentA = new RenderSegment();
        RenderSegment segmentB = new RenderSegment();
        GLListToken token;
        BridgeSupport.bindSegment(segmentA);
        try {
            token = DisplayListGuard.beginList();
            assertNotNull(token, "主线程已补货的 stash 段内可用");
            DisplayListGuard.endList();
            assertTrue(DisplayListGuard.callList(token), "同段内调用本帧新 token 放行");
        } finally {
            BridgeSupport.unbindSegment();
        }

        BridgeSupport.bindSegment(segmentB);
        try {
            assertFalse(DisplayListGuard.callList(token), "fresh-token 护栏：本帧他段新建按未命中处理");
        } finally {
            BridgeSupport.unbindSegment();
        }

        // 跨帧后解除护栏：display list 已在上一帧完成编译
        queue.rotateFrame();
        BridgeSupport.bindSegment(segmentB);
        try {
            assertTrue(DisplayListGuard.callList(token), "跨帧后段内调用放行");
        } finally {
            BridgeSupport.unbindSegment();
        }
    }

    @Test
    void mainThreadCallOfWorkerFreshTokenAllowed() {
        // 主线程（无段绑定）不受护栏限制：编排器屏障保证串行锚点段登记序
        // 恒晚于已完成的并行段，重放序「编译先于调用」成立
        GLListToken warm = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        RenderSegment segment = new RenderSegment();
        GLListToken token;
        BridgeSupport.bindSegment(segment);
        try {
            token = DisplayListGuard.beginList();
            DisplayListGuard.endList();
        } finally {
            BridgeSupport.unbindSegment();
        }
        assertTrue(DisplayListGuard.callList(token), "主线程串行段调用本帧 worker 新 token 放行");
    }

    @Test
    void refillPreSeedsStashForSegments() {
        // 模拟渲染线程帧尾补货：glGenLists 真实调用在无上下文环境不可执行，
        // 此处验证补货后的段内零阻塞语义已由
        // beginListInBoundSegmentWithEmptyStashReturnsNull 的反面覆盖——
        // 主线程首次 beginList 触发批量预生成后，段内 beginList 直接命中 stash
        GLListToken warm = DisplayListGuard.beginList();
        DisplayListGuard.endList();
        assertEquals(1, queue.uncountedGetCallCount);

        RenderSegment segment = new RenderSegment();
        BridgeSupport.bindSegment(segment);
        try {
            GLListToken token = DisplayListGuard.beginList();
            assertNotNull(token, "stash 有货时段内 beginList 零阻塞");
            DisplayListGuard.endList();
            assertTrue(DisplayListGuard.callList(token));
        } finally {
            BridgeSupport.unbindSegment();
        }
        assertEquals(1, queue.uncountedGetCallCount, "段内分配不触发新的批量预生成");
    }
}
