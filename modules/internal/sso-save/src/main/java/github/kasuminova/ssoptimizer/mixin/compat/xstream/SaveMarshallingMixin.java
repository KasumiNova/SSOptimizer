package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.mapper.Mapper;
import github.kasuminova.ssoptimizer.common.save.SavePermissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.thoughtworks.xstream.core.TreeMarshaller", remap = false)
public abstract class SaveMarshallingMixin {
    @Shadow
    private Mapper mapper;

    @Inject(method = "convertAnother(Ljava/lang/Object;Lcom/thoughtworks/xstream/converters/Converter;)V",
            at = @At("HEAD"), remap = false)
    private void ssoptimizer$checkWrittenType(final Object value, final Converter converter, final CallbackInfo ci) {
        SavePermissions.check(mapper, value.getClass(), null);
    }
}
