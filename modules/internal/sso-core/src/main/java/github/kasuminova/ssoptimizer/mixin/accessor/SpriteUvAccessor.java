package github.kasuminova.ssoptimizer.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.fs.graphics.Sprite")
public interface SpriteUvAccessor {
    @Accessor(value = "texX", remap = false)
    float ssoptimizer$getTexX();

    @Accessor(value = "texY", remap = false)
    float ssoptimizer$getTexY();

    @Accessor(value = "texWidth", remap = false)
    float ssoptimizer$getTexWidth();

    @Accessor(value = "texHeight", remap = false)
    float ssoptimizer$getTexHeight();
}
