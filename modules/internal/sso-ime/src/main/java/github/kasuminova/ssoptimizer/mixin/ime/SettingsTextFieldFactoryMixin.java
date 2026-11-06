package github.kasuminova.ssoptimizer.mixin.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import github.kasuminova.ssoptimizer.common.input.ime.TextFieldImeHooks;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.STARFARER_SETTINGS_TEXT_FIELD_OWNER)
public abstract class SettingsTextFieldFactoryMixin {
    @Inject(method = GameMixinSignatures.TextFieldIme.CREATE_TEXT_FIELD + "(Ljava/lang/String;Ljava/lang/String;)"
            + GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC, at = @At("RETURN"), remap = false)
    private void ssoptimizer$registerCreatedTextField(final String text, final String font,
                                                       final CallbackInfoReturnable<TextFieldAPI> cir) {
        TextFieldImeHooks.registerCreatedTextField(cir.getReturnValue());
    }
}
