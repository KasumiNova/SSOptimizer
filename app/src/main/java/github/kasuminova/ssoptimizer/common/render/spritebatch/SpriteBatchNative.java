package github.kasuminova.ssoptimizer.common.render.spritebatch;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.nio.ByteBuffer;

/**
 * Sprite 合批收集的 native 入口：单次 JNI 调用完成 guard 检查
 * （矩阵模式 / stencil / scissor / FBO）、blendEquation 捕获、
 * 收集时刻 MVP 矩阵读取与 quad 顶点打包，替代 Java 侧的 7 次 LWJGL JNI。
 * <p>
 * 组键（纹理 / blend / 方程）管理留在 Java 侧，本类只负责状态读取与写入。
 * native 库缺失时由 {@link SpriteBatchImpl} 回退到 Java 打包路径。
 */
public final class SpriteBatchNative {
    /** guard 拒绝（矩阵模式非 MODELVIEW / scissor / FBO 绑定），未写入任何数据。 */
    public static final int RESULT_GUARD_REJECTED    = -1;
    /** 当前 run 已有内容且混合方程与期望不一致，未写入；调用方 flush 后以 expected=-1 重试。 */
    public static final int RESULT_EQUATION_MISMATCH = -2;
    /** 传入缓冲不是 direct ByteBuffer（Java 侧契约错误，不应发生）。 */
    public static final int RESULT_INVALID_BUFFER    = -3;
    /** stencil 或 alpha test 已启用（扩展状态区），未写入；调用方走 Java 扩展状态捕获路径。 */
    public static final int RESULT_EXTENDED_STATE    = -4;
    /** 当前 run 处于扩展状态区但本次提交不在（要求扩展状态而未满足），未写入；调用方 flush 后重试。 */
    public static final int RESULT_STATE_MISMATCH    = -5;

    static {
        NativeRuntime.ensureLoaded();
    }

    private SpriteBatchNative() {
    }

    /**
     * 检查收集 guard 并向 scratch 追加一个烘焙到裁剪空间的 quad。
     *
     * @param verts                  顶点 scratch（direct，写入偏移 = pendingQuads × 80 字节）
     * @param indices                索引 scratch（direct，写入偏移 = pendingQuads × 12 字节）
     * @param pendingQuads           当前 run 已累积的 quad 数（同时决定写入偏移与 baseVertex）
     * @param expectedBlendEquation  当前 run 的混合方程；pendingQuads &gt; 0 时必须匹配，
     *                               否则返回 {@link #RESULT_EQUATION_MISMATCH}；-1 表示不检查
     * @param requireExtendedState   当前 run 是否处于扩展状态区（stencil/alpha test）：
     *                               1 时若本次提交不在扩展状态区则返回 {@link #RESULT_STATE_MISMATCH}；
     *                               0 时若本次提交处于扩展状态区则返回 {@link #RESULT_EXTENDED_STATE}
     *                               （扩展状态区的状态捕获与打包由 Java 侧完成）
     * @param posX                   sprite 左下角 X（已含 offsetX）
     * @param posY                   sprite 左下角 Y（已含 offsetY）
     * @param width                  sprite 宽
     * @param height                 sprite 高
     * @param centerX                旋转枢轴 X（-1 表示取 width/2）
     * @param centerY                旋转枢轴 Y（-1 表示取 height/2）
     * @param angle                  旋转角（度）
     * @param r                      顶点色 R（0..255）
     * @param g                      顶点色 G
     * @param b                      顶点色 B
     * @param a                      顶点色 A
     * @param texX                   UV 起点 U
     * @param texY                   UV 起点 V
     * @param texWidth               UV 宽
     * @param texHeight              UV 高
     * @return 成功时返回收集时刻的 GL_BLEND_EQUATION 值（&gt;0）；失败时返回负数结果码
     */
    static native int nativeSubmit(ByteBuffer verts, ByteBuffer indices,
                                   int pendingQuads, int expectedBlendEquation, int requireExtendedState,
                                   float posX, float posY, float width, float height,
                                   float centerX, float centerY, float angle,
                                   int r, int g, int b, int a,
                                   float texX, float texY, float texWidth, float texHeight);
}
