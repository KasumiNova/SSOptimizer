package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.common.loading.script.JaninoScriptCompilerCoordinator;
import org.codehaus.janino.JavaSourceClassLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(targets = "org.codehaus.janino.JavaSourceClassLoader")
public abstract class JaninoJavaSourceClassLoaderMixin {
    @Inject(method = "findClass(Ljava/lang/String;)Ljava/lang/Class;", at = @At("HEAD"), remap = false)
    private void ssoptimizer$warmupScriptCache(final String className,
                                               final CallbackInfoReturnable<Class<?>> cir) {
        JaninoScriptCompilerCoordinator.warmup((JavaSourceClassLoader) (Object) this, className);
    }

    @Inject(method = "generateBytecodes(Ljava/lang/String;)Ljava/util/Map;", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reuseScriptCache(final String className,
                                              final CallbackInfoReturnable<Map<String, byte[]>> cir) {
        final Map<String, byte[]> cached = JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(
                (JavaSourceClassLoader) (Object) this,
                className
        );
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "generateBytecodes(Ljava/lang/String;)Ljava/util/Map;", at = @At("RETURN"), cancellable = true, remap = false)
    private void ssoptimizer$persistGeneratedBytecodes(final String className,
                                                       final CallbackInfoReturnable<Map<String, byte[]>> cir) {
        // Janino 编译输出不经过 LaunchClassLoader，需在此执行 GL owner 重定向后再写入按渲染模式隔离的缓存。
        final Map<String, byte[]> bytecodes = RenderThreadRedirector.redirectAll(cir.getReturnValue());
        if (bytecodes != cir.getReturnValue()) {
            cir.setReturnValue(bytecodes);
        }
        JaninoScriptCompilerCoordinator.cacheGeneratedBytecodes(
                (JavaSourceClassLoader) (Object) this,
                className,
                bytecodes
        );
    }
}
