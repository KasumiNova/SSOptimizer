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
 * 可见性协调。SharedDrawable 解折叠后（见 {@link SharedDrawable}），fence 恢复
 * 真实 GPU 序语义，由「Java 会合点（{@link FrameFence}）+ 真实 GL sync 对象
 * （{@link GLSync#realSync()}）」双层承载：
 * <ul>
 *   <li>{@link #glFenceSync}：主线程录制 {@link SignalFenceCommand}，命令体在
 *       渲染线程的命令流序列点创建真实 sync 并附着句柄后完成 fence；aux 原生
 *       线程原生直执并创建即附着（fence 预 signal）；</li>
 *   <li>{@link #glWaitSync}：主线程录制 {@link WaitFenceCommand}（未 signal 帧
 *       悬挂续跑，渲染线程不阻塞），放行后追加真实 glWaitSync 建立跨上下文
 *       GPU 序；aux 线程原生直执；</li>
 *   <li>{@link #glClientWaitSync}：latch 等待段不变（超时语义保留），通过后
 *       追加真实 glClientWaitSync（主线程经阻塞通道在渲染线程执行，aux 线程
 *       原生直执）；</li>
 *   <li>{@link #glDeleteSync}：句柄标记失效 + 真实 sync 删除（主线程入队一条
 *       删除命令，aux 线程原生直执）。</li>
 * </ul>
 */
public final class GL32 {
    private GL32() {
    }

    /** aux 侧 glWaitSync 遇到主产 fence 真实 sync 未附着的恢复路径一次性 WARN 标记。 */
    private static volatile boolean auxWaitWithoutRealWarned;

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
     * 主线程：录制 fence 信号命令并返回关联句柄。句柄立即有效（指向已入队的
     * 会合点），无需阻塞等待渲染线程；真实 sync 由信号命令体在渲染线程的命令流
     * 序列点创建并附着（附着 happens-before fence signal）。
     * <p>
     * aux 原生线程：真实 fence 立即插入 aux 上下文命令流（原生直执），真实 sync
     * 创建即附着，Java 会合点预 signal（等待方读真实 sync 即可，无需会合）。
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
        if (BridgeSupport.recordingContext().auxNative) {
            Object real = BridgeSupport.syncOps().fenceSync(condition, flags);
            FrameFence fence = new FrameFenceImpl();
            fence.signal();
            return new GLSync(fence, real);
        }
        FrameFence fence = new FrameFenceImpl();
        GLSync handle = new GLSync(fence);
        RenderQueue queue = BridgeSupport.queue();
        queue.currentFrame().addFence(fence);
        queue.submit(new SignalFenceCommand(fence, () -> {
            Object real = BridgeSupport.syncOps().fenceSync(condition, flags);
            handle.attachReal(real);
            if (handle.isDeleted()) {
                // 句柄在信号命令执行前已被 glDeleteSync：随建随删，避免真实 sync 泄漏
                BridgeSupport.syncOps().deleteSync(real);
            }
        }));
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "FENCE_SIG", System.identityHashCode(fence), 0, 0, null);
        return handle;
    }

    /**
     * 录制 fence 等待命令。{@code sync} 必须来自本 bridge 的
     * {@link #glFenceSync}（ASM 重定向保证模组拿到的只有本 bridge 的句柄）。
     * <p>
     * 主线程：{@link WaitFenceCommand} 放行（fence 已 signal）后追加真实
     * glWaitSync——aux 产的 fence 在主上下文命令流上建立 GPU 序的关键。
     * <p>
     * aux 原生线程：真实 sync 已附着则原生直执；未附着（主产 fence 的信号命令
     * 尚未执行，真实 sync 不存在）属恢复路径——原生语义下无对象可服务端等待，
     * 一次性 WARN 后跳过（BoxUtil 的 tryGLSync 协议保证消费相位晚于信号相位，
     * 稳态不应命中）。
     *
     * @param sync    glFenceSync 返回的句柄
     * @param flags   保留参数，LWJGL2 语义下必须为 0
     * @param timeout LWJGL2 语义下固定为 GL_TIMEOUT_IGNORED，原样保留
     */
    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        if (BridgeSupport.recordingContext().auxNative) {
            Object real = sync.realSync();
            if (real == null) {
                if (!auxWaitWithoutRealWarned) {
                    auxWaitWithoutRealWarned = true;
                    org.apache.log4j.Logger.getLogger(GL32.class).warn(
                            "[SSOptimizer] aux 线程 glWaitSync 遇到主产 fence 的真实 sync 未附着"
                                    + "（信号命令尚未执行），本次服务端等待跳过（恢复路径，稳态不应出现）");
                }
                return;
            }
            BridgeSupport.syncOps().waitSync(real, flags, timeout);
            return;
        }
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "FENCE_WAIT", System.identityHashCode(sync.fence()), 0, 0, null);
        BridgeSupport.enqueue(new WaitFenceCommand(sync.fence(), () -> {
            Object real = sync.realSync();
            if (real != null) {
                BridgeSupport.syncOps().waitSync(real, flags, timeout);
            }
        }));
    }

    /**
     * 标记句柄失效并删除真实 sync（已附着时）。主线程入队一条删除命令（程序序
     晚于该线程的全部等待命令；GL 规范允许删除仍有待决等待的 sync 对象），
     * aux 线程原生直执；未附着（主产 fence 信号命令未执行）时由信号命令体
     * 随建随删。幂等。
     */
    public static void glDeleteSync(GLSync sync) {
        sync.markDeleted();
        final Object real = sync.realSync();
        if (real == null) {
            return;
        }
        if (BridgeSupport.recordingContext().auxNative) {
            BridgeSupport.syncOps().deleteSync(real);
            return;
        }
        BridgeSupport.enqueue(() -> BridgeSupport.syncOps().deleteSync(real));
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
     * 等待 fence 完成。latch 等待段（先经 {@link BridgeSupport#flushForFenceWait()}
     * 提交当前帧，通道选择理由见该方法 javadoc；aux 原生线程上 flush 为空操作）
     * 通过后追加真实 glClientWaitSync：主线程无 GL 上下文，经阻塞通道在渲染线程
     * 执行；aux 原生线程原生直执。真实等待使用 latch 段的剩余超时预算。
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
        final Object real = sync.realSync();
        if (real == null) {
            // 帧失败强制 signal 的恢复路径：真实 sync 未创建，无 GPU 等待对象
            return org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
        }
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED;
        }
        if (BridgeSupport.recordingContext().auxNative) {
            return BridgeSupport.syncOps().clientWaitSync(real, flags, remaining);
        }
        final int[] result = new int[1];
        BridgeSupport.blockingWaitResource(
                () -> result[0] = BridgeSupport.syncOps().clientWaitSync(real, flags, remaining));
        return result[0];
    }

    /**
     * fence 状态查询：CPU 侧近似（无真实 GL 回读），
     * 支持 GL_OBJECT_TYPE/GL_SYNC_STATUS/GL_SYNC_CONDITION/GL_SYNC_FLAGS
     * 四个标准 pname。GL_SYNC_STATUS 反映的是「命令流到达」（latch signal）
     * 而非 GPU 完成——真实 GPU 完成状态查询需求未出现（BoxUtil 只做存在性/
     * 类型校验），出现时再升级为真实 glGetSync 回读。
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

    /** 桥句柄即 sync 身份：未被 glDeleteSync 标记失效即为 true。 */
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
