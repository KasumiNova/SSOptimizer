package github.kasuminova.ssoptimizer.common.render.warroom;

import com.fs.graphics.TextureObject;

/**
 * 纹理条带批量渲染执行器。
 * <p>
 * 动机：指挥界面（warroom）任务连线在一帧内会产生大量同纹理、同混合模式的
 * {@code renderTexturedStrip} 调用，逐条提交会造成每条纹理绑定与 JNI/draw call 开销。
 * 本接口由 {@code TexturedStripRenderHelper} 实现，用于把 {@link WarroomTaskLineBatch}
 * 收集到的一整段条带一次性提交给 GL。
 * <p>
 * 几何数组布局：每条带连续 9 个 float，依次为
 * startX, startY, endX, endY, startWidth, endWidth,
 * startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale。
 * 颜色数组布局：每条带 1 个 int，按 0xRRGGBBAA 打包。
 */
public interface StripBatchRenderer {
    /**
     * 一次性渲染一批条带。
     * <p>
     * 实现方只应读取数组前 {@code stripCount} 条带的数据；数组容量可能大于实际条带数，
     * 且数组为调用方复用的内部缓冲，实现方不得保留引用。
     *
     * @param texture    本批共用的纹理
     * @param additive   本批共用的混合模式（true 为加法混合）
     * @param geometry   几何数组，每条带 9 个 float（见类注释）
     * @param colors     颜色数组，每条带 1 个 0xRRGGBBAA 打包 int
     * @param stripCount 实际条带数量
     */
    void renderStripBatch(TextureObject texture, boolean additive,
                          float[] geometry, int[] colors, int stripCount);
}
