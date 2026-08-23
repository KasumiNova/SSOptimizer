package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * aux 生产者线程活动纪元失效的端到端验证（主线程 getter 状态仿真 × BoxUtil 式
 * 后台线程提交的交叉场景）。
 * <p>
 * 用真实 {@link RenderQueueImpl}（起真实渲染线程），但 aux 提交的命令体为空操作、
 * 再同步屏障的采样来源替换为桩（{@link BridgeSupport#stateSnapshotSourceForTesting}），
 * 全程不触碰真实 GL。验证点：
 * <ul>
 *   <li>无 aux 活动时主线程仿真 getter 走簿记快路径（不触发屏障）；</li>
 *   <li>aux 线程提交后，主线程 getter 回退屏障读真值并采入簿记（标量/FBO/矩阵）；</li>
 *   <li>屏障每提交段至多一次（滞后窗口），aux 静止后不再重复屏障；</li>
 *   <li>aux 再次活动时下一检查点重新再同步。</li>
 * </ul>
 */
class AuxActivityInvalidationTest {

    private static final int SNAPSHOT_PROGRAM = 42;
    private static final int SNAPSHOT_FRAMEBUFFER = 99;
    private static final float SNAPSHOT_PROJECTION_M0 = 3.5f;

    private RenderQueueImpl queue;
    private AtomicInteger snapshotInvocations;

    @BeforeEach
    void setUp() {
        queue = new RenderQueueImpl();
        GL11.install(queue);
        // 认领主录制线程（静态 mainThread 可能被前序用例认领迁移，swap 即重新认领）
        BridgeSupport.swapFramesAndSync();
        snapshotInvocations = new AtomicInteger();
        BridgeSupport.stateSnapshotSourceForTesting(() -> {
            snapshotInvocations.incrementAndGet();
            GlStateSnapshot snapshot = new GlStateSnapshot();
            snapshot.currentProgram = SNAPSHOT_PROGRAM;
            snapshot.framebufferBinding = SNAPSHOT_FRAMEBUFFER;
            snapshot.projectionTop[0] = SNAPSHOT_PROJECTION_M0;
            return snapshot;
        });
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
        if (queue != null) {
            queue.shutdown();
        }
    }

    /** 模拟 BoxUtil 后台线程：经队列提交通道提交一条命令（空操作命令体）。 */
    private void auxSubmit() throws InterruptedException {
        Thread aux = new Thread(() -> queue.submit(() -> {
        }), "simulated-boxutil-aux");
        aux.start();
        aux.join(5000);
        assertFalse(aux.isAlive(), "aux 提交线程必须正常结束");
    }

    @Test
    void mainThreadGetterUsesSimulationWhenNoAuxActivity() {
        BridgeSupport.simulatedState().onUseProgram(7);
        assertEquals(7, GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM),
                "无 aux 活动时仿真簿记直接命中");
        assertEquals(0, snapshotInvocations.get(), "无 aux 活动不得触发屏障再同步");
    }

    @Test
    void auxSubmissionInvalidatesSimulationAndResyncs() throws InterruptedException {
        BridgeSupport.simulatedState().onUseProgram(7);
        assertEquals(7, GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM));

        auxSubmit();

        assertEquals(SNAPSHOT_PROGRAM, GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM),
                "aux 提交后仿真簿记必须失效，getter 回退屏障读真值");
        assertEquals(1, snapshotInvocations.get(), "失效后应发生恰好一次屏障再同步");
        // FBO 绑定跟踪同属被 aux 污染的簿记，必须随屏障一并复位
        assertEquals(SNAPSHOT_FRAMEBUFFER,
                GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT),
                "FBO 绑定跟踪必须随屏障再同步采入真值");
        // 矩阵读取（ASTD uploadMvp 的 GL_PROJECTION 回读路径）同样采入快照值
        FloatBuffer matrix = FloatBuffer.allocate(16);
        GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, matrix);
        assertEquals(SNAPSHOT_PROJECTION_M0, matrix.get(0),
                "矩阵栈顶必须随屏障再同步采入真值");
    }

    @Test
    void resyncBarrierHappensAtMostOncePerSubmittedSegment() throws InterruptedException {
        auxSubmit();
        GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        GL11.glGetBoolean(org.lwjgl.opengl.GL11.GL_BLEND);
        assertEquals(1, snapshotInvocations.get(),
                "同一提交段内的后续仿真 getter 不得重复屏障（滞后窗口）");
    }

    @Test
    void noNewBarrierAfterResyncWhileAuxIdle() throws InterruptedException {
        auxSubmit();
        assertEquals(SNAPSHOT_PROGRAM, GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM));
        assertEquals(1, snapshotInvocations.get());

        // 推进到下一提交段；aux 静止（纪元未再变化）时不得重复屏障
        BridgeSupport.swapFramesAndSync();
        assertEquals(SNAPSHOT_PROGRAM, GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM),
                "再同步采入的值在 aux 静止期保持有效");
        assertEquals(1, snapshotInvocations.get(), "aux 静止后不得重复屏障");
    }

    @Test
    void renewedAuxActivityTriggersNewResync() throws InterruptedException {
        auxSubmit();
        GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        assertEquals(1, snapshotInvocations.get());

        BridgeSupport.swapFramesAndSync();
        auxSubmit();

        GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        assertEquals(2, snapshotInvocations.get(), "aux 再次活动后下一检查点必须重新再同步");
    }
}
