package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.mapper.Mapper;
import github.kasuminova.ssoptimizer.common.save.SavePermissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.thoughtworks.xstream.mapper.AttributeMapper", remap = false)
public abstract class SaveAttributeTypesMixin {
    @Inject(method = "getConverterFromItemType(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)Lcom/thoughtworks/xstream/converters/SingleValueConverter;",
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$checkWrittenAttributeType(final String fieldName, final Class<?> type,
                                                       final Class<?> owner,
                                                       final CallbackInfoReturnable<SingleValueConverter> cir) {
        if (cir.getReturnValue() != null) {
            SavePermissions.check((Mapper) this, type, null);
        }
    }

    @Inject(method = "getConverterFromAttribute(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Lcom/thoughtworks/xstream/converters/SingleValueConverter;",
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$checkAttributeType(final Class<?> owner, final String fieldName,
                                                final Class<?> type,
                                                final CallbackInfoReturnable<SingleValueConverter> cir) {
        if (cir.getReturnValue() != null) {
            SavePermissions.check((Mapper) this, type, null);
        }
    }
}
