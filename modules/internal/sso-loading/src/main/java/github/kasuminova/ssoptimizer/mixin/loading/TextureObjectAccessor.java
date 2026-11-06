package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = GameClassNames.TEXTURE_OBJECT_DOTTED)
public interface TextureObjectAccessor {
    @Accessor(value = "textureId", remap = false)
    int ssoptimizer$readTextureId();

    @Mutable
    @Accessor(value = "textureId", remap = false)
    void ssoptimizer$writeTextureId(int textureId);
}
