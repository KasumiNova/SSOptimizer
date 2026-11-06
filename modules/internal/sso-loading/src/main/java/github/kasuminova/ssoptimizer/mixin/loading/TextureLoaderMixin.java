package github.kasuminova.ssoptimizer.mixin.loading;

import com.fs.graphics.TextureLoader;
import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.loading.FastResourceImageDecoder;
import github.kasuminova.ssoptimizer.common.loading.LazyTextureManager;
import github.kasuminova.ssoptimizer.common.loading.TextureDimensionSupport;
import github.kasuminova.ssoptimizer.common.loading.TexturePixelConversionResult;
import github.kasuminova.ssoptimizer.common.loading.TexturePixelConverter;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * 像素转换、纹理尺寸与路径加载整体交给 loading 模块的实现；图片解码改走快速解码器。
 * 原 ASM 拆出的 eager 原实现没有调用方，迁移时不再保留。
 * GL 上传调用的织入目标随渲染线程模式变化，见 TextureLoaderUploadMixin 与 TextureLoaderUploadDirectMixin。
 */
@Mixin(targets = GameClassNames.TEXTURE_LOADER_DOTTED)
public abstract class TextureLoaderMixin {
    @Shadow(remap = false)
    private HashMap<String, TextureObject> textureCache;

    @Shadow(remap = false)
    private Color upperHalfColor;

    @Shadow(remap = false)
    private Color averageColor;

    @Shadow(remap = false)
    private Color lowerHalfColor;

    @Overwrite(remap = false)
    private ByteBuffer convertPixels(final BufferedImage image, final TextureObject texture) {
        final TexturePixelConversionResult result = TexturePixelConverter.convert(image);
        texture.setTextureHeight(result.textureHeight());
        texture.setTextureWidth(result.textureWidth());
        this.upperHalfColor = result.upperHalfColor();
        this.averageColor = result.averageColor();
        this.lowerHalfColor = result.lowerHalfColor();
        return result.buffer();
    }

    @Overwrite(remap = false)
    private int textureDimension(final int size) {
        return TextureDimensionSupport.textureDimension(size);
    }

    @Overwrite(remap = false)
    public TextureObject loadTexture(final String path) throws IOException {
        return LazyTextureManager.loadTexture((TextureLoader) (Object) this, this.textureCache, path);
    }

    @Redirect(method = "readImage", remap = false,
            at = @At(value = "INVOKE",
                    target = "Ljavax/imageio/ImageIO;read(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;"))
    private BufferedImage ssoptimizer$decodeImage(final InputStream inputStream,
                                                  final String path) throws IOException {
        return FastResourceImageDecoder.decode(path, inputStream);
    }
}
