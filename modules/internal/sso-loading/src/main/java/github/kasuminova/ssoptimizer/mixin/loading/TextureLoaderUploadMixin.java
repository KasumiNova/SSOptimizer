package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureUploadHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

/**
 * 渲染线程分离模式开启时，RenderThreadRedirectTransformer 先于 Mixin 把纹理上传改写为 bridge 的 GL11 调用，
 * 因此本 Mixin 以 bridge 的 GL11 为目标，仅在该模式开启时织入。模式关闭时由 TextureLoaderUploadDirectMixin 处理原始 LWJGL 调用。
 */
@Mixin(targets = GameClassNames.TEXTURE_LOADER_DOTTED)
public abstract class TextureLoaderUploadMixin {
    @Redirect(method = {"createTextureFromImage", "loadTextureData"}, remap = false,
            at = @At(value = "INVOKE",
                    target = "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GL11;glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V"))
    private static void ssoptimizer$glTexImage2D(final int target, final int level, final int internalFormat,
                                                 final int width, final int height, final int border,
                                                 final int format, final int type, final ByteBuffer pixels) {
        TextureUploadHelper.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    @Redirect(method = "loadTextureData", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GL11;glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V"))
    private static void ssoptimizer$glTexSubImage2D(final int target, final int level, final int xOffset,
                                                    final int yOffset, final int width, final int height,
                                                    final int format, final int type, final ByteBuffer pixels) {
        TextureUploadHelper.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }
}
