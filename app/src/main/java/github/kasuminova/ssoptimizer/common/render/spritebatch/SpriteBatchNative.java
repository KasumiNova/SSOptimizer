package github.kasuminova.ssoptimizer.common.render.spritebatch;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Sprite 合批收集的 native 入口：单次 JNI 调用完成 guard 检查
 * （矩阵模式 / stencil / scissor）、渲染目标（FBO + viewport）捕获、
 * blendEquation 捕获、收集时刻 MVP 矩阵读取与 quad 顶点打包，替代 Java 侧的 7 次 LWJGL JNI。
 * <p>
 * FBO 离屏 pass（GraphicsLib 光照 renderForeground/drawNormalMaps 等）不再被拒绝：
 * 渲染目标纳入 run 状态键（{@code runTarget} 缓冲），flush 时回放收集时刻的
 * FBO 绑定与 viewport，保证延迟 flush 仍落在正确的渲染目标上。
 * <p>
 * 组键（纹理 / blend / 方程）管理留在 Java 侧，本类只负责状态读取与写入。
 * native 库缺失时由 {@link SpriteBatchImpl} 回退到 Java 打包路径。
 */
public final class SpriteBatchNative {
    /** guard 拒绝（矩阵模式非 MODELVIEW / stencil / scissor），未写入任何数据。 */
    public static final int RESULT_GUARD_REJECTED    = -1;
    /** 当前 run 已有内容且混合方程与期望不一致，未写入；调用方 flush 后以 expected=-1 重试。 */
    public static final int RESULT_EQUATION_MISMATCH = -2;
    /** 传入缓冲不是 direct ByteBuffer（Java 侧契约错误，不应发生）。 */
    public static final int RESULT_INVALID_BUFFER    = -3;
    /** alpha test 已启用（扩展状态区），未写入；调用方走 Java 扩展状态捕获路径。 */
    public static final int RESULT_EXTENDED_STATE    = -4;
    /** 当前 run 已有内容且本次提交的状态与 run 不一致
     * （要求扩展状态而未满足 / 渲染目标 FBO+viewport 已切换），未写入；调用方 flush 后重试。 */
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
     * @param runTarget              当前 run 的渲染目标（direct IntBuffer ≥5 槽：
     *                               [0]=GL_FRAMEBUFFER_BINDING，[1..4]=GL_VIEWPORT xyzw）；
     *                               pendingQuads &gt; 0 时作为期望值比较，不一致返回
     *                               {@link #RESULT_STATE_MISMATCH}（缓冲保持原值）；
     *                               pendingQuads == 0 时写入收集时刻的捕获值
     *                               （后续返回 {@link #RESULT_EXTENDED_STATE} 时同样已写入）
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
    static native int nativeSubmit(ByteBuffer verts, ByteBuffer indices, IntBuffer runTarget,
                                   int pendingQuads, int expectedBlendEquation, int requireExtendedState,
                                   float posX, float posY, float width, float height,
                                   float centerX, float centerY, float angle,
                                   int r, int g, int b, int a,
                                   float texX, float texY, float texWidth, float texHeight);

    /**
     * 单次 JNI 完成一个 run 的全部 flush 绘制（约 25 次 LWJGL 调用折叠为一次跨界）：
     * 状态保存（pushAttrib/pushClientAttrib、FBO 绑定与 viewport 显式保存）、run 状态回放
     * （收集时刻的 FBO + viewport / 纹理 / blend / 混合方程 / alpha test，stencil 显式关闭）、
     * 单位矩阵化、VBO 顶点指针设置与 glDrawElements、矩阵与属性及渲染目标恢复。
     * <p>
     * FBO 与 viewport 回放收集时刻的捕获值而非 flush 时刻上下文：flush 可能被延迟到
     * pass 切换之后触发（组切换 flush 发生在下一次 submit 时），必须保证绘制落在
     * 收集时刻的渲染目标上；绘制后恢复 flush 入口处的 FBO 绑定与 viewport。
     * <p>
     * 注意：本方法返回时两个 VBO 目标仍绑定在合批器自身缓冲上，且恢复绑定的职责在
     * Java 侧（必须经 LWJGL 重绑）。LWJGL2 的 glColorPointer 等 Buffer 校验读取的是
     * StateTracker 跟踪值而非真实 GL 状态，native（glad）恢复绑定不会更新 tracker，
     * 若不在 Java 侧重绑会导致后续 LWJGL Buffer 绘制误判 "Array Buffer Object is enabled"。
     *
     * @param vertexVboId       顶点 VBO ID（数据已由 Java 侧 DynamicVbo.write 上传）
     * @param vertexBase        本 run 顶点在 VBO 中的起始字节偏移
     * @param indexVboId        索引 VBO ID
     * @param indexBase         本 run 索引在 VBO 中的起始字节偏移
     * @param quadCount         本 run 的 quad 数
     * @param textureId         纹理 ID
     * @param blendSrc          blend 源因子
     * @param blendDest         blend 目标因子
     * @param blendEquation     混合方程（收集时刻捕获）
     * @param fbo               渲染目标 FBO 绑定（收集时刻捕获，0 = 默认帧缓冲）
     * @param viewportX         渲染目标 viewport X（收集时刻捕获）
     * @param viewportY         渲染目标 viewport Y
     * @param viewportW         渲染目标 viewport 宽
     * @param viewportH         渲染目标 viewport 高
     * @param alphaTestEnabled  扩展状态区：alpha test 是否启用
     * @param alphaFunc         alpha 比较函数
     * @param alphaRef          alpha 参考值
     * @param r                 绘制结束后恢复的当前颜色 R（原版残留语义：run 最后 sprite 的颜色）
     * @param g                 颜色 G
     * @param b                 颜色 B
     * @param a                 颜色 A
     * @param prevMatrixMode    调用方矩阵模式（VBO 上传前捕获）
     */
    static native void nativeFlush(int vertexVboId, long vertexBase,
                                   int indexVboId, long indexBase,
                                   int quadCount,
                                   int textureId, int blendSrc, int blendDest, int blendEquation,
                                   int fbo, int viewportX, int viewportY, int viewportW, int viewportH,
                                   boolean alphaTestEnabled, int alphaFunc, float alphaRef,
                                   int r, int g, int b, int a,
                                   int prevMatrixMode);
}
