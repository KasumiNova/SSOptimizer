package ssoptimizer.standalone.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ssoptimizer.standalone.host.PreparedSources;

import java.util.List;

@Mixin(targets = "com.fs.starfarer.loading.scripts.ScriptStore", remap = false)
public abstract class ScriptStoreMixin {
    @Shadow private static volatile List<String> jarFiles;

    @ModifyVariable(method = "createSourceClassLoader(Ljava/lang/ClassLoader;)V", at = @At("HEAD"), argsOnly = true)
    private static ClassLoader ssoptimizer$namedScriptParent(ClassLoader original) {
        return PreparedSources.scriptParent(original, jarFiles);
    }
}
