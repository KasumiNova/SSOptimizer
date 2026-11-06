package github.kasuminova.ssoptimizer.mixin.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.ui.ActionListener;
import com.fs.starfarer.util.InputEvent;
import github.kasuminova.ssoptimizer.common.input.ime.TextFieldImeHooks;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameClassNames.TEXT_FIELD_IMPL)
public abstract class TextFieldImplementationMixin {
    @Inject(method = "<init>(Ljava/lang/String;Ljava/lang/String;Lcom/fs/starfarer/ui/ActionListener;)V",
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$registerCreatedTextField(final String text, final String font,
                                                       final ActionListener actionListener,
                                                       final CallbackInfo ci) {
        TextFieldImeHooks.registerCreatedTextField((TextFieldAPI) (Object) this);
    }

    @Inject(method = "<init>(Ljava/lang/String;Ljava/lang/String;ZLcom/fs/starfarer/ui/ActionListener;)V",
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$registerCreatedTextFieldWithMinimalMode(final String text, final String font,
                                                                      final boolean minimalMode,
                                                                      final ActionListener actionListener,
                                                                      final CallbackInfo ci) {
        TextFieldImeHooks.registerCreatedTextField((TextFieldAPI) (Object) this);
    }

    @Inject(method = GameMixinSignatures.TextFieldIme.GRAB_FOCUS
            + GameMixinSignatures.TextFieldIme.GRAB_FOCUS_DESC, at = @At("RETURN"), remap = false)
    private void ssoptimizer$onTextFieldFocusGained(final boolean playSound, final CallbackInfo ci) {
        TextFieldImeHooks.onTextFieldFocusGained((TextFieldAPI) (Object) this);
    }

    @Inject(method = GameMixinSignatures.TextFieldIme.RELEASE_FOCUS
            + GameMixinSignatures.TextFieldIme.RELEASE_FOCUS_DESC, at = @At("RETURN"), remap = false)
    private void ssoptimizer$onTextFieldFocusLost(final InputEvent event, final CallbackInfo ci) {
        TextFieldImeHooks.onTextFieldFocusLost((TextFieldAPI) (Object) this);
    }
}
