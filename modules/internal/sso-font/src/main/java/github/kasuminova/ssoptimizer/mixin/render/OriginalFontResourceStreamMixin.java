package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.font.OriginalGameFontOverrides;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;

@Mixin(targets = GameClassNames.RESOURCE_LOADER_DOTTED)
public abstract class OriginalFontResourceStreamMixin {
    @Inject(method = GameMemberNames.ResourceLoader.OPEN_STREAM
            + "(Ljava/lang/String;)Ljava/io/InputStream;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$openOriginalFontResource(final String resourcePath,
                                                      final CallbackInfoReturnable<InputStream> cir) {
        final InputStream stream = OriginalGameFontOverrides.openStream(resourcePath);
        if (stream != null) {
            cir.setReturnValue(stream);
        }
    }
}
