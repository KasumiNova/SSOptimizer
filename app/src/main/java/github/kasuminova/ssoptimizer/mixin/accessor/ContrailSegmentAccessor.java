package github.kasuminova.ssoptimizer.mixin.accessor;

import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.fs.starfarer.combat.entities.ContrailEngine$Oo")
public interface ContrailSegmentAccessor {
    @Accessor(value = "ø00000", remap = false)
    Vector2f ssoptimizer$getPosition();

    @Accessor(value = "return", remap = false)
    Vector2f ssoptimizer$getNormal();

    @Accessor(value = "Object", remap = false)
    float ssoptimizer$getWidth();

    @Accessor(value = "Ø00000", remap = false)
    float ssoptimizer$getMaxAge();

    @Accessor(value = "o00000", remap = false)
    float ssoptimizer$getProgress();

    @Accessor(value = "Ô00000", remap = false)
    float ssoptimizer$getAlphaMult();

    @Accessor(value = "Ò00000", remap = false)
    float ssoptimizer$getU();
}