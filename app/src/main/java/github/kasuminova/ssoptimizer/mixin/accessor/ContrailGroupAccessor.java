package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.loading.specs.EngineSlot;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.awt.*;
import java.util.List;

@Mixin(targets = "com.fs.starfarer.combat.entities.ContrailEngine$o")
public interface ContrailGroupAccessor {
    @Accessor(value = "oO0000", remap = false)
    List<Object> ssoptimizer$getSegments();

    @Accessor(value = "ÒO0000", remap = false)
    com.fs.graphics.Object ssoptimizer$getTexture();

    @Accessor(value = "for", remap = false)
    Vector2f ssoptimizer$getTail();

    @Accessor(value = "Õ00000", remap = false)
    Color ssoptimizer$getColor();

    @Accessor(value = "ö00000", remap = false)
    EngineSlot.BlendMode ssoptimizer$getBlendMode();
}