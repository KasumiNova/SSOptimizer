package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.script.JaninoScriptCompilerCoordinator;
import org.codehaus.janino.JavaSourceClassLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Janino 脚本类加载器的 Mixin 注入。
 * <p>
 * 注入目标：{@code org.codehaus.janino.JavaSourceClassLoader}<br>
 * 注入动机：该类只需要在公开编译入口前后插入缓存与预热逻辑，使用 Mixin 头尾注入即可稳定完成；
 * 相比整段改写方法体的 ASM，Mixin 更容易维护，也能避免校验器对栈帧的苛刻报错。<br>
 * 注入效果：在 {@code findClass(String)} 入口触发脚本缓存预热，并让
 * {@code generateBytecodes(String)} 先尝试命中磁盘缓存、再在原始 Janino 编译返回后写回缓存。
 */
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

    @Inject(method = "generateBytecodes(Ljava/lang/String;)Ljava/util/Map;", at = @At("RETURN"), remap = false)
    private void ssoptimizer$persistGeneratedBytecodes(final String className,
                                                       final CallbackInfoReturnable<Map<String, byte[]>> cir) {
        JaninoScriptCompilerCoordinator.cacheGeneratedBytecodes(
                (JavaSourceClassLoader) (Object) this,
                className,
                cir.getReturnValue()
        );
    }
}
