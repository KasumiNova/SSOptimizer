package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;

/** 仅跳过同一录制帧内与上一条完全相邻且参数相同的状态命令；实例按生产者线程隔离。 */
final class StateDedup {
    private static final int UNKNOWN = -1;

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
    static final int TYPE_CLEAR_DEPTH = 24;
    static final int TYPE_DEPTH_RANGE = 25;

    private int lastType = UNKNOWN;
    private int lastA;
    private int lastB;
    private int lastC;
    private int lastD;
    private int lastCommitSeq;

    boolean shouldSkip(RenderFrame frame, int type, int a, int b, int c, int d) {
        if (lastType != type || lastA != a || lastB != b || lastC != c || lastD != d) {
            return false;
        }
        return frame.commitSeq() == lastCommitSeq;
    }

    void record(RenderFrame frame, int type, int a, int b, int c, int d) {
        lastType = type;
        lastA = a;
        lastB = b;
        lastC = c;
        lastD = d;
        lastCommitSeq = frame.commitSeq();
    }

    void invalidate() {
        lastType = UNKNOWN;
    }
}
