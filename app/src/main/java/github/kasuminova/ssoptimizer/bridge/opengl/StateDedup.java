package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;

/**
 * 录制侧状态命令去重：对连续相同的高频状态命令（glBindTexture/glEnable/
 * glDisable/glBlendFunc 等，见 {@link BridgeSupport#enqueueState} 的调用点）
 * 只入队一次，渲染线程不再重复执行冗余的状态设置（v36 profile：状态变换
 * 10.9% + 纹理绑定 9.5% 的主线程录制热点）。
 * <p>
 * <b>去重模型（相邻性判定）</b>：本类跟踪「上一条已入队的状态命令」的类型与
 * 参数指纹，外加其入队时刻所在命令段的 {@link RenderSegment#commitSeq()}。
 * 新状态命令仅在「类型与参数全部相同 <b>且</b> 自上次入队以来本段命令列表
 * 没有任何插入」时被跳过。段在回放时连续执行（帧提交前按段登记序拼接），
 * 故「相邻」只需考察本段内的插入——段内任何其他命令（glCallList、顶点流落帧、
 * draw/矩阵/纹理上传等）都会使 commitSeq 变化，从而打断相邻性；其他段的并发
 * 录制与本段的执行相邻性无关。跨段不去重：段边界（绑定/切换/帧边界）由调用路径
 * 负责 {@link #invalidate()}，段首状态命令强制入队（保守）。这是对旁路审计结论
 * （见 docs/design/render-state-dedup.md）的落地：dedup 状态跟踪无需感知具体旁路，
 * commitSeq 判据天然覆盖一切「命令流插入 = 状态可能已变」的边界。
 * <p>
 * 线程模型：本类随 {@link RecordingContext} 按生产者线程隔离；主线程在帧
 * 边界（swap）与串行段切换时刷新 {@link RecordingContext#dedupSegment} 并
 * {@link #invalidate()} 重置缓存（跨帧不延续去重——GL 状态虽跨帧保持，
 * 保守起见帧间状态命令照常入队，避免任何跨帧时序依赖）。非主线程每次经
 * {@link BridgeSupport#queue()} 取当前帧的串行段做相邻性校验（低频，可接受）。
 */
final class StateDedup {
    /** 无有效缓存（未知/已失效）标记。 */
    private static final int UNKNOWN = -1;

    // 去重状态命令类型（GL11 各状态入口在 enqueueState 处引用；参数统一按
    // 最多 4 个 int 槽位打包，float 由调用点转 Float.floatToRawIntBits）
    static final int TYPE_BIND_TEXTURE = 0;
    static final int TYPE_ENABLE = 1;
    static final int TYPE_DISABLE = 2;
    static final int TYPE_BLEND_FUNC = 3;
    static final int TYPE_ALPHA_FUNC = 4;
    static final int TYPE_SHADE_MODEL = 5;
    static final int TYPE_LINE_WIDTH = 6;
    static final int TYPE_POINT_SIZE = 7;
    static final int TYPE_POLYGON_MODE = 8;
    static final int TYPE_HINT = 9;
    static final int TYPE_DEPTH_MASK = 10;
    static final int TYPE_DEPTH_FUNC = 11;
    static final int TYPE_CULL_FACE = 12;
    static final int TYPE_FRONT_FACE = 13;
    static final int TYPE_COLOR_MASK = 14;
    static final int TYPE_STENCIL_FUNC = 15;
    static final int TYPE_STENCIL_OP = 16;
    static final int TYPE_STENCIL_MASK = 17;
    static final int TYPE_SCISSOR = 18;
    static final int TYPE_VIEWPORT = 19;
    static final int TYPE_CLEAR_COLOR = 20;
    static final int TYPE_CLEAR_STENCIL = 21;
    static final int TYPE_PIXEL_STOREI = 22;
    static final int TYPE_MATRIX_MODE = 23;

    private int lastType = UNKNOWN;
    private int lastA;
    private int lastB;
    private int lastC;
    private int lastD;
    private int lastCommitSeq;

    /**
     * 判断新状态命令是否应被跳过（与上一条去重状态命令类型/参数相同，
     * 且期间本段命令列表无任何插入）。
     *
     * @param segment 当前录制段（相邻性判据的 commitSeq 来源）
     */
    boolean shouldSkip(RenderSegment segment, int type, int a, int b, int c, int d) {
        if (lastType != type || lastA != a || lastB != b || lastC != c || lastD != d) {
            return false;
        }
        return segment.commitSeq() == lastCommitSeq;
    }

    /** 记录一条已入队的状态命令（调用方需在提交后调用，commitSeq 含本命令）。 */
    void record(RenderSegment segment, int type, int a, int b, int c, int d) {
        lastType = type;
        lastA = a;
        lastB = b;
        lastC = c;
        lastD = d;
        lastCommitSeq = segment.commitSeq();
    }

    /** 使缓存失效（帧边界/段边界调用；此后任何状态命令照常入队）。 */
    void invalidate() {
        lastType = UNKNOWN;
    }
}
