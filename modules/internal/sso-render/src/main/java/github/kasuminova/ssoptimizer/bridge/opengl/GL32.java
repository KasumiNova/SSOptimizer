package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;
import github.kasuminova.ssoptimizer.common.render.queue.FrameFenceImpl;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.queue.SignalFenceCommand;
import github.kasuminova.ssoptimizer.common.render.queue.WaitFenceCommand;

/**
 * org.lwjgl.opengl.GL32 的 bridge 镜像（fence sync 族 + base-vertex/实例化绘制族
 * + FBO/多重采样状态族）。
 * <p>
 * 动机：BoxUtil 用 glFenceSync/glWaitSync/glDeleteSync 做跨上下文的 GPU 命令流
 * 可见性协调。aux-context 折叠进单渲染线程后（见 {@link SharedDrawable}），fence
 * 退化为队列内会合点：
 * <ul>
 *   <li>{@link #glFenceSync} 录制 {@link SignalFenceCommand}（渲染线程执行到该
 *       点时完成 fence——此前的渲染命令已全部进入执行流），并立即返回关联的
 *       {@link GLSync} 句柄；</li>
 *   <li>{@link #glWaitSync} 录制 {@link WaitFenceCommand}，执行时检查 fence
 *       是否已 signal：已 signal 直接放行，未 signal 则帧悬挂续跑（渲染线程
 *       不阻塞，协议细节见 WaitFenceCommand 类 javadoc）；</li>
 *   <li>{@link #glDeleteSync} 只做句柄失效标记：fence 是纯 Java 对象，无真实
 *       GPU 资源需要释放。</li>
 * </ul>
 * 骨架阶段 SignalFenceCommand 的命令体只完成 fence；接入游戏后如需与真实 GPU
 * 时间线对齐（性能分析等），再在命令体内追加真实 glFenceSync 调用。
 */
public final class GL32 {
    private GL32() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    /**
     * 录制 fence 信号命令并返回关联句柄。句柄立即有效（指向已入队的会合点），
     * 无需阻塞等待渲染线程。
     * <p>
     * fence 同时登记到当前帧（{@link RenderFrame#addFence}）：帧执行失败丢弃
     * 剩余命令时，队列会强制完成登记过的 fence——否则信号命令随失败帧被丢弃，
     * 等待该 fence 的悬挂续跑任务将永久自旋。
     *
     * @param condition LWJGL2 语义下固定为 GL_SYNC_GPU_COMMANDS_COMPLETE，原样保留
     * @param flags     保留参数，LWJGL2 语义下必须为 0
     * @return 关联本次 fence 的不透明句柄
     */
    public static GLSync glFenceSync(int condition, int flags) {
        FrameFence fence = new FrameFenceImpl();
        RenderQueue queue = BridgeSupport.queue();
        queue.currentFrame().addFence(fence);
        queue.submit(new SignalFenceCommand(fence));
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "FENCE_SIG", System.identityHashCode(fence), 0, 0, null);
        return new GLSync(fence);
    }

    /**
     * 录制 fence 等待命令。{@code sync} 必须来自本 bridge 的
     * {@link #glFenceSync}（ASM 重定向保证模组拿到的只有本 bridge 的句柄）。
     *
     * @param sync    glFenceSync 返回的句柄
     * @param flags   保留参数，LWJGL2 语义下必须为 0
     * @param timeout LWJGL2 语义下固定为 GL_TIMEOUT_IGNORED，原样保留
     */
    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "FENCE_WAIT", System.identityHashCode(sync.fence()), 0, 0, null);
        BridgeSupport.enqueue(new WaitFenceCommand(sync.fence()));
    }

    /**
     * 标记句柄失效。折叠模型下 sync 对象是纯 Java 会合点，无队列命令产生；
     * 幂等，与真实 glDeleteSync 对同一 sync 多次调用未定义的语义不冲突。
     */
    public static void glDeleteSync(GLSync sync) {
        sync.markDeleted();
    }

    // ------------------------------------------------------------------
    // 盘点补面：64 位 getter（BoxUtil 等的健康校验/能力探测）
    // ------------------------------------------------------------------

    /** 64 位 getter：阻塞通道取回。 */
    public static long glGetInteger64(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL32.glGetInteger64(pname));
    }

    /** 带索引 64 位 getter：阻塞通道取回。 */
    public static long glGetInteger64(int pname, int index) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL32.glGetInteger64(pname, index));
    }

    // ------------------------------------------------------------------
    // 盘点补面：sync 等待/查询（BoxUtil Operation$Sync 引用）
    // ------------------------------------------------------------------

    /**
     * 等待 fence 完成。折叠模型下 fence 是纯 Java 会合点（{@link #glFenceSync}），
     * 等待在调用线程自旋（先经 {@link BridgeSupport#flushForFenceWait()} 提交当前帧，
     * 通道选择理由见该方法 javadoc），不触碰真实 GL。
     *
     * @return {@code GL_CONDITION_SATISFIED}（已 signal）或 {@code GL_TIMEOUT_EXPIRED}
     */
    public static int glClientWaitSync(GLSync sync, int flags, long timeout) {
        FrameFence fence = sync.fence();
        BridgeSupport.flushForFenceWait();
        final long deadline = System.nanoTime() + timeout;
        while (!fence.isSignaled()) {
            if (System.nanoTime() - deadline >= 0) {
                return org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED;
            }
            Thread.onSpinWait();
        }
        return org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
    }

    /**
     * fence 状态查询：折叠模型下是纯 CPU 查询（无真实 GL sync 对象），
     * 支持 GL_OBJECT_TYPE/GL_SYNC_STATUS/GL_SYNC_CONDITION/GL_SYNC_FLAGS
     * 四个标准 pname。
     */
    public static int glGetSynci(GLSync sync, int pname) {
        switch (pname) {
            case org.lwjgl.opengl.GL32.GL_OBJECT_TYPE:
                return org.lwjgl.opengl.GL32.GL_SYNC_FENCE;
            case org.lwjgl.opengl.GL32.GL_SYNC_STATUS:
                return sync.fence().isSignaled()
                        ? org.lwjgl.opengl.GL32.GL_SIGNALED : org.lwjgl.opengl.GL32.GL_UNSIGNALED;
            case org.lwjgl.opengl.GL32.GL_SYNC_CONDITION:
                return org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;
            case org.lwjgl.opengl.GL32.GL_SYNC_FLAGS:
                return 0;
            default:
                throw new IllegalArgumentException("[SSOptimizer] 不支持的 glGetSynci pname: 0x"
                        + Integer.toHexString(pname));
        }
    }

    /**
     * buffer 形态批量查询：折叠模型下每个 pname 恒为单值（真实 GL 对 sync 全部
     * pname 也只返回一个值），写入 values 当前位置；length 非 null 时写入 1。
     * <p>
     * 必须镜像的理由（BoxUtil 1.0.6 Operation$Sync.init:3308 崩溃根因）：
     * GLWrapper 以方法引用 {@code GL32::glGetSync} 挂 lambda，impl Handle 未镜像时
     * 保持 lwjgl owner 而 instantiatedMethodType 的身份类型（GLSync）被改写，
     * 两侧类型不一致 → LambdaConversionException。sync 身份族必须全族镜像。
     *
     * @param length 可选（可为 null），接收实际写入值个数
     * @param values 接收查询值，remaining 至少 1
     */
    public static void glGetSync(GLSync sync, int pname, java.nio.IntBuffer length,
                                 java.nio.IntBuffer values) {
        if (values == null || values.remaining() < 1) {
            throw new IllegalArgumentException("[SSOptimizer] glGetSync values 缓冲容量不足: "
                    + (values == null ? "null" : values.remaining()));
        }
        values.put(glGetSynci(sync, pname));
        if (length != null) {
            length.put(1);
        }
    }

    /** 单值便捷形态：语义同 {@link #glGetSynci}（LWJGL2 中两入口等价）。 */
    public static int glGetSync(GLSync sync, int pname) {
        return glGetSynci(sync, pname);
    }

    /** 折叠模型下桥句柄即 sync 对象：未被 glDeleteSync 标记失效即为 true。 */
    public static boolean glIsSync(GLSync sync) {
        return !sync.isDeleted();
    }

    // ------------------------------------------------------------------
    // 盘点补面：base-vertex / 实例化绘制族（BoxUtil 1.0.6 GLWrapper$Drawcall
    // 引用；录制语义同 {@link ARBDrawInstanced}：索引 buffer 快照入队，VBO 偏移传值）
    // ------------------------------------------------------------------

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsBaseVertex(int mode, java.nio.ByteBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, snapshot, baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsBaseVertex(int mode, java.nio.IntBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, snapshot.asIntBuffer(), baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsBaseVertex(int mode, java.nio.ShortBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, snapshot.asShortBuffer(), baseVertex));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glDrawElementsBaseVertex(int mode, int count, int type, long indicesOffset, int baseVertex) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(mode, count, type, indicesOffset, baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end,
                                                     java.nio.ByteBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(mode, start, end, snapshot, baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end,
                                                     java.nio.IntBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(mode, start, end,
                        snapshot.asIntBuffer(), baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end,
                                                     java.nio.ShortBuffer indices, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(mode, start, end,
                        snapshot.asShortBuffer(), baseVertex));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end, int count, int type,
                                                     long indicesOffset, int baseVertex) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glDrawRangeElementsBaseVertex(
                mode, start, end, count, type, indicesOffset, baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseVertex(int mode, java.nio.ByteBuffer indices,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(mode, snapshot, primcount, baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseVertex(int mode, java.nio.IntBuffer indices,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(mode, snapshot.asIntBuffer(),
                        primcount, baseVertex));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseVertex(int mode, java.nio.ShortBuffer indices,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(mode, snapshot.asShortBuffer(),
                        primcount, baseVertex));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indicesOffset,
                                                         int primcount, int baseVertex) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glDrawElementsInstancedBaseVertex(
                mode, count, type, indicesOffset, primcount, baseVertex));
    }

    // ------------------------------------------------------------------
    // 盘点补面：FBO / 多重采样状态族（BoxUtil 引用，纯值参数直接入队）
    // ------------------------------------------------------------------

    public static void glFramebufferTexture(int target, int attachment, int texture, int level) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL32.glFramebufferTexture(target, attachment, texture, level));
    }

    public static void glTexImage2DMultisample(int target, int samples, int internalformat,
                                               int width, int height, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glTexImage2DMultisample(
                target, samples, internalformat, width, height, fixedSampleLocations));
    }

    public static void glTexImage3DMultisample(int target, int samples, int internalformat,
                                               int width, int height, int depth, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glTexImage3DMultisample(
                target, samples, internalformat, width, height, depth, fixedSampleLocations));
    }

    public static void glSampleMaski(int maskNumber, int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL32.glSampleMaski(maskNumber, mask));
    }
}
