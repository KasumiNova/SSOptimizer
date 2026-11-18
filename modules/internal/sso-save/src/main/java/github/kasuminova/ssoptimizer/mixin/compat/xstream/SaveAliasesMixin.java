package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import github.kasuminova.ssoptimizer.common.save.SaveAliasAccess;
import github.kasuminova.ssoptimizer.common.save.SaveAliases;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.thoughtworks.xstream.mapper.ClassAliasingMapper", remap = false)
public abstract class SaveAliasesMixin implements SaveAliasAccess {
    @Unique
    private final SaveAliases ssoptimizer$aliases = new SaveAliases();

    @Inject(method = {"addClassAlias(Ljava/lang/String;Ljava/lang/Class;)V",
            "addTypeAlias(Ljava/lang/String;Ljava/lang/Class;)V"}, at = @At("RETURN"), remap = false)
    private void ssoptimizer$recordAlias(final String name, final Class<?> type, final CallbackInfo ci) {
        ssoptimizer$aliases.register(name, type);
    }

    @Override
    public SaveAliases ssoptimizer$saveAliases() {
        return ssoptimizer$aliases;
    }
}
