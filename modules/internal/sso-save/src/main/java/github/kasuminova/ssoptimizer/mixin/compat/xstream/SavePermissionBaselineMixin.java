package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import com.thoughtworks.xstream.XStream;
import github.kasuminova.ssoptimizer.common.save.SavePermissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.thoughtworks.xstream.XStream", remap = false)
public abstract class SavePermissionBaselineMixin {
    @Inject(method = "<init>(Lcom/thoughtworks/xstream/converters/reflection/ReflectionProvider;Lcom/thoughtworks/xstream/io/HierarchicalStreamDriver;Lcom/thoughtworks/xstream/core/ClassLoaderReference;Lcom/thoughtworks/xstream/mapper/Mapper;Lcom/thoughtworks/xstream/converters/ConverterLookup;Lcom/thoughtworks/xstream/converters/ConverterRegistry;)V",
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$rememberPermissionDefaults(final CallbackInfo ci) {
        SavePermissions.rememberDefaults((XStream) (Object) this);
    }
}
