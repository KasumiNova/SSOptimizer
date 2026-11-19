package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.font.BitmapFontRenderer;
import com.fs.starfarer.renderers.FloatingText;
import github.kasuminova.ssoptimizer.common.font.FloatingTextFont;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FloatingText.class, remap = false)
public abstract class FloatingTextFontMixin {
    @Shadow private BitmapFontRenderer fontRenderer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ssoptimizer$markFont(final CallbackInfo ci) {
        ((FloatingTextFont) fontRenderer).ssoptimizer$useFloatingGlyphs();
    }
}
