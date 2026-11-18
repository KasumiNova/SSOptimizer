package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import github.kasuminova.ssoptimizer.common.font.FontGlyphSources;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.BITMAP_FONT_RENDERER_DOTTED, remap = false)
public abstract class BitmapFontMetricsMixin {
    @Shadow(remap = false)
    private BitmapFont font;

    @Redirect(method = {
            "getTextWidth(Ljava/lang/String;)F",
            "getClosestCharIndex(II)I",
            "getCharPosition(I)Lorg/lwjgl/util/vector/Vector2f;",
            "getCharWidth(I)F",
            "getCharHeight(I)F",
            "drawTextWrapped(FF)V",
            "drawTextWrappedCjk(FF)V"
    }, at = @At(value = "INVOKE", target = "Lcom/fs/graphics/font/BitmapFont;getGlyph(I)Lcom/fs/graphics/font/BitmapGlyph;"), remap = false)
    private BitmapGlyph ssoptimizer$missingLayoutGlyph(final BitmapFont source, final int codePoint) {
        final BitmapGlyph original = source.getGlyph(codePoint);
        if (original != null) {
            return original;
        }
        return FontGlyphSources.missingGlyphMetrics(source, codePoint);
    }

    @Inject(method = "hasGlyph(I)Z", at = @At("RETURN"), cancellable = true, remap = false)
    private void ssoptimizer$hasDynamicGlyph(final int codePoint, final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            cir.setReturnValue(FontGlyphSources.missingGlyphMetrics(font, codePoint) != null);
        }
    }
}
