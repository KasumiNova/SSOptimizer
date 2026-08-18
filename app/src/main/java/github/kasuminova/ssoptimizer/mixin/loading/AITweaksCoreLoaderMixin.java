package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * AITweaks 自有类加载器的 GL 重定向注入。
 * <p>
 * 注入目标：{@code com.genir.aitweaks.launcher.loading.CoreLoader#loadClass(String)}<br>
 * 注入动机：AITweaks 用自有 {@code URLClassLoader}（CoreLoader）加载 aitweaks-core.jar
 * （自带 Transformer 混淆处理后经 defineClass 定义），完全不经过 LaunchClassLoader 的
 * transformer 链——渲染线程分离模式下其 lwjgl 调用不会被 ASM 重定向到 bridge，
 * 在主线程直出即 {@code No OpenGL context found in the current thread}。<br>
 * 注入效果：在 defineClass 调用点改写参数字节码——与 Janino 脚本类同路的
 * {@link RenderThreadRedirector#redirect(String, byte[])} 处理（非分离模式零开销原样返回）；
 * 改写后字节数可能变化，offset/length 参数同步重写。<br>
 * 用 {@link ModifyArgs} 而非 {@code @Redirect}：defineClass 是 protected final，
 * handler 不便直接代为调用；改参数让原调用照常发生。
 */
@Mixin(targets = "com.genir.aitweaks.launcher.loading.CoreLoader")
public abstract class AITweaksCoreLoaderMixin {
    @ModifyArgs(method = "loadClass(Ljava/lang/String;)Ljava/lang/Class;", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/genir/aitweaks/launcher/loading/CoreLoader;defineClass(Ljava/lang/String;[BII)Ljava/lang/Class;"))
    private void ssoptimizer$redirectCoreClassBytes(final Args args) {
        final String dottyName = args.get(0);
        final byte[] bytes = args.get(1);
        final byte[] redirected = RenderThreadRedirector.redirect(dottyName, bytes);
        if (redirected != bytes) {
            args.set(1, redirected);
            args.set(2, 0);
            args.set(3, redirected.length);
        }
    }
}
