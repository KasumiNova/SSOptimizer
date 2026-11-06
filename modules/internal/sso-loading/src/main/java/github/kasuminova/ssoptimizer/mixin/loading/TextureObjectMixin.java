package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.LazyTextureManager;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = GameClassNames.TEXTURE_OBJECT_DOTTED)
public abstract class TextureObjectMixin {

    @Shadow(remap = false, aliases = "bindTarget")
    private int ssoptimizer$bindTarget;

    @Shadow(remap = false, aliases = "textureId")
    private int ssoptimizer$textureId;

    @Overwrite(remap = false)
    public void bind() {
        LazyTextureManager.bindTexture((com.fs.graphics.TextureObject) (Object) this, ssoptimizer$bindTarget);
    }

    @Overwrite(remap = false)
    public int getTextureId() {
        return LazyTextureManager.getTextureId(
                (com.fs.graphics.TextureObject) (Object) this, ssoptimizer$bindTarget, ssoptimizer$textureId);
    }
}
