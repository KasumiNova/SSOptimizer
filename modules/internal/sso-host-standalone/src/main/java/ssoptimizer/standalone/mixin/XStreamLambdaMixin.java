package ssoptimizer.standalone.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.thoughtworks.xstream.core.util.Types", remap = false)
public abstract class XStreamLambdaMixin {
    @Inject(method = "isLambdaType(Ljava/lang/Class;)Z", at = @At("HEAD"), cancellable = true)
    private static void ssoptimizer$recognizeHiddenLambda(Class<?> type, CallbackInfoReturnable<Boolean> result) {
        if (type != null && type.isSynthetic() && type.isHidden() && type.getName().matches(".*\\$\\$Lambda(?:\\$[0-9]+)?/.*")) {
            result.setReturnValue(true);
        }
    }
}
