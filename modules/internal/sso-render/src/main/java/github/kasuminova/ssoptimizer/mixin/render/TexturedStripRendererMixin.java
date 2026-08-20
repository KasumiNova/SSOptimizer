package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.TexturedStripRenderHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.awt.Color;

/**
 * 引擎纹理条带渲染方法的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.renderers.TexturedStripRenderer#renderTexturedStrip(...)}<br>
 * 注入动机：原版的逐段绘制逻辑每次调用都会执行独立的 GL 状态切换和 draw call；
 * 通过替换为 {@link TexturedStripRenderHelper} 的优化实现，可以减少冗余的 GL 状态切换。<br>
 * 注入效果：整个方法体替换为 {@code TexturedStripRenderHelper.renderTexturedStrip()} 的纯参数透传委托。
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
}
