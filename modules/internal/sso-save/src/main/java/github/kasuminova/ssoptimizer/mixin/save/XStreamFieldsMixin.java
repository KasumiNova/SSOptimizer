package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.XStreamFieldAccessHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Mixin(targets = "com.thoughtworks.xstream.core.util.Fields")
public abstract class XStreamFieldsMixin {
    @Inject(method = "read", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$read(final Field field, final Object target, final CallbackInfoReturnable<Object> cir) {
        if (XStreamFieldAccessHelper.supports(field)) cir.setReturnValue(XStreamFieldAccessHelper.read(field, target));
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$write(final Field field, final Object target, final Object value, final CallbackInfo ci) {
        if (XStreamFieldAccessHelper.supports(field) && !Modifier.isFinal(field.getModifiers())) {
            XStreamFieldAccessHelper.write(field, target, value);
            ci.cancel();
        }
    }
}
