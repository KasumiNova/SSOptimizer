package github.kasuminova.ssoptimizer.mixin.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import github.kasuminova.ssoptimizer.common.input.ime.TextFieldImeHooks;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.STANDARD_TOOLTIP_V2_EXPANDABLE)
public abstract class TooltipTextFieldFactoryMixin {
    @Inject(method = GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD + "(FF)"
            + GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC, at = @At("RETURN"), remap = false)
    private void ssoptimizer$registerCreatedTextField(final float width, final float height,
                                                       final CallbackInfoReturnable<TextFieldAPI> cir) {
        TextFieldImeHooks.registerCreatedTextField(cir.getReturnValue());
    }

    @Inject(method = GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD + "(FLjava/lang/String;F)"
            + GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC, at = @At("RETURN"), remap = false)
    private void ssoptimizer$registerCreatedTextFieldWithFont(final float width, final String font,
                                                               final float height,
                                                               final CallbackInfoReturnable<TextFieldAPI> cir) {
        TextFieldImeHooks.registerCreatedTextField(cir.getReturnValue());
    }

    @Inject(method = GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD + "(FFLjava/lang/String;F)"
            + GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC, at = @At("RETURN"), remap = false)
    private void ssoptimizer$registerCreatedTextFieldWithSizeAndFont(final float width, final float height,
                                                                      final String font, final float padding,
                                                                      final CallbackInfoReturnable<TextFieldAPI> cir) {
        TextFieldImeHooks.registerCreatedTextField(cir.getReturnValue());
    }
}
