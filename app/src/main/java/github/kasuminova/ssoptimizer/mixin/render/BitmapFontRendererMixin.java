package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import github.kasuminova.ssoptimizer.common.font.RuntimeScaledFontCache;
import github.kasuminova.ssoptimizer.common.render.engine.BitmapFontRendererHelper;
import github.kasuminova.ssoptimizer.common.render.engine.TextLayoutDiagnostics;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BitmapFontRenderer 文本绘制路径的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.font.BitmapFontRenderer}<br>
 * 注入动机：原版逐字形渲染会产生大量 Java 侧 GL 调用，文本绘制路径在高 DPI 和大字号下开销明显；
 * 通过重写关键方法，可把单个字形输出折叠为 helper/native 调用。<br>
 * 注入效果：{@code drawGlyph} 整体替换为 {@link BitmapFontRendererHelper#renderGlyphQuad} 委托并
 * 记录字形布局诊断；{@code render()} 入口注入运行时字体缩放换字体逻辑。
 */
@Mixin(targets = GameClassNames.BITMAP_FONT_RENDERER_DOTTED)
public abstract class BitmapFontRendererMixin {

    @Shadow(remap = false, aliases = "font")
    private BitmapFont ssoptimizer$font;

    @Shadow(remap = false, aliases = "requestedFontSize")
    private float ssoptimizer$requestedFontSize;

    @Shadow(remap = false, aliases = "shadowCopies")
    private int ssoptimizer$shadowCopies;

    @Shadow(remap = false, aliases = "shadowScale")
    private float ssoptimizer$shadowScale;

    /**
     * 渲染入口：按屏幕缩放解析换用缩放字体，并回写调整后的请求字号。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器在 render()V 入口插入字体缩放换字体逻辑，迁移为等价的 @Inject(HEAD)。
     */
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ssoptimizer$resolveScaledFontAtRenderHead(CallbackInfo ci) {
        ssoptimizer$font = (BitmapFont) RuntimeScaledFontCache.resolveScaledFont(
                ssoptimizer$font, ssoptimizer$requestedFontSize);
        ssoptimizer$requestedFontSize = RuntimeScaledFontCache.adjustRequestedFontSize(
                ssoptimizer$font, ssoptimizer$requestedFontSize);
    }

    /**
     * 单个字形输出整体替换为 helper/native 委托。
     *
     * @param x        字形基准 X
     * @param y        字形基准 Y
     * @param glyph    字形度量
     * @param scale    当前字号缩放
     * @param blend    混合标记（替换体不区分，保持签名一致）
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 drawGlyph 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    private void drawGlyph(float x, float y, BitmapGlyph glyph, float scale, boolean blend) {
        TextLayoutDiagnostics.recordGlyphLayout(
                glyph.getGlyphId(), glyph.getXOffset(), glyph.getXAdvance(),
                System.identityHashCode(ssoptimizer$font), scale, ssoptimizer$requestedFontSize,
                ssoptimizer$font.getNominalFontSize(), ssoptimizer$font.getLineHeight());
        BitmapFontRendererHelper.renderGlyphQuad(
                x, y,
                glyph.getWidth(), glyph.getHeight(), glyph.getBearingY(),
                glyph.getTexX(), glyph.getTexY(), glyph.getTexWidth(), glyph.getTexHeight(),
                scale, ssoptimizer$shadowCopies, ssoptimizer$shadowScale);
    }
}
