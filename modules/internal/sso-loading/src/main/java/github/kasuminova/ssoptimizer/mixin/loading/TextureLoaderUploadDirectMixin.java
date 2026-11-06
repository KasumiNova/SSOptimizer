package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureUploadHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

/**
 * 渲染线程分离模式关闭时不存在 GL 重定向，纹理上传保留原始 LWJGL 的 GL11 调用，
 * 本 Mixin 仅在该模式关闭时织入。模式开启时由 TextureLoaderUploadMixin 处理 bridge 调用。
 */
@Mixin(targets = GameClassNames.TEXTURE_LOADER_DOTTED)
public abstract class TextureLoaderUploadDirectMixin {
    @Redirect(method = {"createTextureFromImage", "loadTextureData"}, remap = false,
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL11;glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V"))
    private static void ssoptimizer$glTexImage2DDirect(final int target, final int level, final int internalFormat,
                                                       final int width, final int height, final int border,
                                                       final int format, final int type, final ByteBuffer pixels) {
        TextureUploadHelper.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    @Redirect(method = "loadTextureData", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL11;glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V"))
    private static void ssoptimizer$glTexSubImage2DDirect(final int target, final int level, final int xOffset,
                                                          final int yOffset, final int width, final int height,
                                                          final int format, final int type, final ByteBuffer pixels) {
        TextureUploadHelper.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }
}
