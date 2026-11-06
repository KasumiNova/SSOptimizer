package github.kasuminova.ssoptimizer.mixin.accessor;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = GameClassNames.CONTRAIL_SEGMENT_DOTTED)
public interface ContrailSegmentAccessor {
    @Accessor(value = "position", remap = false)
    Vector2f ssoptimizer$getPosition();

    @Accessor(value = "normal", remap = false)
    Vector2f ssoptimizer$getNormal();

    @Accessor(value = "vel", remap = false)
    Vector2f ssoptimizer$getVel();

    @Accessor(value = "width", remap = false)
    float ssoptimizer$getWidth();

    @Accessor(value = "width", remap = false)
    void ssoptimizer$setWidth(float width);

    @Accessor(value = "baseWidth", remap = false)
    float ssoptimizer$getBaseWidth();

    @Accessor(value = "maxAge", remap = false)
    float ssoptimizer$getMaxAge();

    @Accessor(value = "progress", remap = false)
    float ssoptimizer$getProgress();

    @Accessor(value = "progress", remap = false)
    void ssoptimizer$setProgress(float progress);

    @Accessor(value = "alphaMult", remap = false)
    float ssoptimizer$getAlphaMult();

    @Accessor(value = "texU", remap = false)
    float ssoptimizer$getU();

    @Accessor(value = "texU", remap = false)
    void ssoptimizer$setTexU(float texU);
}
