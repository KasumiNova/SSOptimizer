package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ArcStripRenderHelper;
import github.kasuminova.ssoptimizer.common.render.engine.TexturedStripRenderHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.awt.Color;

/**
 * 引擎纹理条带/圆弧渲染方法的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.renderers.TexturedStripRenderer} 的
 * {@code renderTexturedStrip(...)}、{@code renderArc(...)}（12 参数重载）与
 * {@code renderLineArc(...)}。<br>
 * 注入动机：原版的逐段绘制逻辑每次调用都会执行独立的 GL 状态切换和 draw call，
 * 且 renderArc/renderLineArc 对每个弧顶点即时计算 sin/cos（campaign 地图
 * {@code EntityIndicator.renderRing} 逐帧逐实体调用，为超空间大图场景的
 * Profiler 热点）；通过替换为 {@link TexturedStripRenderHelper} /
 * {@link ArcStripRenderHelper} 的优化实现，消除冗余状态切换与逐帧三角函数。<br>
 * 注入效果：方法体整体替换为对应 helper 的纯参数透传委托；renderArc 的
 * 11 参数重载原版即委托 12 参数重载，间接受本重写覆盖。
 */
@Mixin(targets = GameClassNames.TEXTURED_STRIP_RENDERER_DOTTED)
public abstract class TexturedStripRendererMixin {

    /**
     * 将整条纹理条带绘制委托给批量化 helper。
     *
     * @param texture             条带纹理
     * @param startX              起始点 X
     * @param startY              起始点 Y
     * @param endX                结束点 X
     * @param endY                结束点 Y
     * @param startWidth          起始段宽度
     * @param endWidth            结束段宽度
     * @param color               条带颜色
     * @param startEdgeAlphaScale 起始边透明度缩放
     * @param centerAlphaScale    中心透明度缩放
     * @param endEdgeAlphaScale   结束边透明度缩放
     * @param additive            是否加法混合
     * @author GitHub Copilot
     * @reason 原 ASM 处理器以纯参数透传整体替换方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public static void renderTexturedStrip(
            TextureObject texture,
            float startX, float startY,
            float endX, float endY,
            float startWidth, float endWidth,
            Color color,
            float startEdgeAlphaScale,
            float centerAlphaScale,
            float endEdgeAlphaScale,
            boolean additive) {
        TexturedStripRenderHelper.renderTexturedStrip(
                texture, startX, startY, endX, endY, startWidth, endWidth,
                color, startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale, additive);
    }

    /**
     * 将圆弧（纹理条带/平滑线）绘制委托给带顶点方向缓存的 helper。
     *
     * @param texture       条带纹理；null 走平滑线（LINE_LOOP/LINE_STRIP）分支
     * @param x             圆心 X
     * @param y             圆心 Y
     * @param startAngle    起始角（度）
     * @param endAngle      结束角（度）
     * @param segmentLength 段长除数（决定段数）
     * @param radius        半径
     * @param width         条带宽度 / 线宽
     * @param color         弧颜色
     * @param alphaStart    弧端 alpha 起点
     * @param alphaEnd      弧中 alpha 峰值
     * @param alphaMult     整体 alpha 倍率（逐帧动态量）
     * @param additive      是否加法混合
     * @reason 原版逐顶点即时计算 sin/cos；helper 按 (step, count) 缓存方向表，
     *         顶点序列与原版位级一致（见 {@link ArcStripRenderHelper} 等价性论证）。
     */
    @Overwrite(remap = false)
    public static void renderArc(
            TextureObject texture,
            float x, float y,
            float startAngle, float endAngle,
            float segmentLength,
            float radius, float width,
            Color color,
            int alphaStart, int alphaEnd,
            float alphaMult,
            boolean additive) {
        ArcStripRenderHelper.renderArc(
                texture, x, y, startAngle, endAngle, segmentLength, radius, width,
                color, alphaStart, alphaEnd, alphaMult, additive);
    }

    /**
     * 将虚线/实线圆弧绘制委托给带顶点方向缓存的 helper。
     *
     * @param x             圆心 X
     * @param y             圆心 Y
     * @param startAngle    起始角（度）
     * @param endAngle      结束角（度）
     * @param segmentLength 段长除数（决定段数）
     * @param radius        半径
     * @param lineWidth     线宽
     * @param color         弧颜色（虚线模式下的第二色）
     * @param dashColor     虚线第二色；null 走实线分支
     * @param dashSegments  虚线分段数
     * @param alphaStart    弧端 alpha 起点
     * @param alphaEnd      弧中 alpha 峰值
     * @param alphaMult     整体 alpha 倍率（逐帧动态量）
     * @param additive      是否加法混合
     * @reason 同 {@link #renderArc}：逐顶点 sin/cos 改为方向表查表，位级等价。
     */
    @Overwrite(remap = false)
    public static void renderLineArc(
            float x, float y,
            float startAngle, float endAngle,
            float segmentLength,
            float radius, float lineWidth,
            Color color, Color dashColor,
            int dashSegments,
            int alphaStart, int alphaEnd,
            float alphaMult,
            boolean additive) {
        ArcStripRenderHelper.renderLineArc(
                x, y, startAngle, endAngle, segmentLength, radius, lineWidth,
                color, dashColor, dashSegments, alphaStart, alphaEnd, alphaMult, additive);
    }
}
