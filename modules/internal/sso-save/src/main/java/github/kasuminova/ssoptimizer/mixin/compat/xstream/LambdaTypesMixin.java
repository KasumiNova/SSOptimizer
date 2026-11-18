package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import github.kasuminova.ssoptimizer.common.save.LambdaTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = "com.thoughtworks.xstream.core.util.Types", remap = false)
public abstract class LambdaTypesMixin {
    @Overwrite(remap = false)
    public static boolean isLambdaType(final Class<?> type) {
        return LambdaTypes.isLambda(type);
    }
}
