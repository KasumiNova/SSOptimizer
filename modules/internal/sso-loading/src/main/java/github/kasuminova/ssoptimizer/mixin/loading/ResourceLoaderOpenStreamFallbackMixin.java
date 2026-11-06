package github.kasuminova.ssoptimizer.mixin.loading;

import com.fs.util.ResourceLoader;
import github.kasuminova.ssoptimizer.common.loading.CaseInsensitiveResourceFallback;
import github.kasuminova.ssoptimizer.common.loading.ResourceLoaderThreadState;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;

/**
 * openStream 的原方法体只有一次 openResource 调用，回退逻辑以该调用点的重定向表达。
 * openResource 会消费线程隔离状态，因此必须在进入 try 之前捕获，避免回退通道跨越模组资源边界。
 */
@Mixin(targets = GameClassNames.RESOURCE_LOADER_DOTTED)
public abstract class ResourceLoaderOpenStreamFallbackMixin {
    @Redirect(method = GameMemberNames.ResourceLoader.OPEN_STREAM + "(Ljava/lang/String;)Ljava/io/InputStream;",
            remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/util/ResourceLoader;openResource(Ljava/lang/String;Z)Ljava/io/InputStream;"))
    private InputStream ssoptimizer$openResourceWithCaseFallback(final ResourceLoader self,
                                                                  final String path,
                                                                  final boolean managed) throws IOException {
        final String sourceFilter = ResourceLoaderThreadState.getSourceFilter();
        final boolean suppressCustomResources = ResourceLoaderThreadState.isSuppressCustomResources();
        try {
            return self.openResource(path, managed);
        } catch (final RuntimeException exception) {
            final InputStream resolved = CaseInsensitiveResourceFallback.tryResolve(
                    path, exception, sourceFilter, suppressCustomResources);
            if (resolved != null) {
                return resolved;
            }
            throw exception;
        }
    }
}
