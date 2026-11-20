package ssoptimizer.standalone.mixin;

import com.thoughtworks.xstream.mapper.DefaultMapper;
import com.thoughtworks.xstream.mapper.Mapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ssoptimizer.standalone.save.SaveFieldBinding;
import ssoptimizer.standalone.save.SaveFieldNames;

@Mixin(value = DefaultMapper.class, remap = false)
public abstract class SaveMemberMapperMixin implements SaveFieldBinding {
    @Unique private volatile boolean ssoptimizer$campaign;

    @Override
    public void ssoptimizer$enableSaveFields() {
        ssoptimizer$campaign = true;
    }

    @Inject(method = "lookupMapperOfType", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$findSaveMapper(Class<?> type, CallbackInfoReturnable<Mapper> callback) {
        if (type == SaveFieldBinding.class) callback.setReturnValue((Mapper) (Object) this);
    }

    @Inject(method = "serializedMember", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$writeFieldName(Class<?> type, String name, CallbackInfoReturnable<String> callback) {
        if (ssoptimizer$campaign && name.startsWith("$sso$")) {
            callback.setReturnValue(SaveFieldNames.original(type, name));
        }
    }

    @Inject(method = "realMember", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$readFieldName(Class<?> type, String name, CallbackInfoReturnable<String> callback) {
        if (ssoptimizer$campaign && SaveFieldNames.requiresEncoding(name)) {
            callback.setReturnValue(SaveFieldNames.physical(type, name));
        }
    }
}
