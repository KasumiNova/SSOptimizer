package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.ui.LabelImpl;
import github.kasuminova.ssoptimizer.common.font.FontGlyphSources;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.TEXT_FIELD_IMPL, remap = false)
public abstract class TextFieldGlyphCoverageMixin {
    @Shadow(remap = false)
    private LabelImpl hintLabel;

    @Inject(method = "isValidChar(C)Z", at = @At("RETURN"), cancellable = true, remap = false)
    private void ssoptimizer$acceptSupportedGlyph(final char character, final CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || character == 0 || character == '%' || character == '$'
                || Character.isSurrogate(character)) {
            return;
        }
        cir.setReturnValue(FontGlyphSources.missingGlyphMetrics(hintLabel.getRenderer().getFont(), character) != null);
    }
}
