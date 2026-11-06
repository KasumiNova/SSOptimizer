package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.starfarer.combat.entities.ContrailEngine;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.awt.*;
import java.util.List;

@Mixin(targets = GameClassNames.CONTRAIL_GROUP_DOTTED)
public interface ContrailGroupAccessor {
    @Accessor(value = "segments", remap = false)
    List<Object> ssoptimizer$getSegments();

    @Accessor(value = "texture", remap = false)
    com.fs.graphics.TextureObject ssoptimizer$getTexture();

    @Accessor(value = "tail", remap = false)
    Vector2f ssoptimizer$getTail();

    @Accessor(value = "color", remap = false)
    Color ssoptimizer$getColor();

    @Accessor(value = "blendMode", remap = false)
    EngineSlot.BlendMode ssoptimizer$getBlendMode();

    @Accessor(value = "ended", remap = false)
    boolean ssoptimizer$getEnded();

    @Accessor(value = "widthMode", remap = false)
    ContrailEngine.ContrailWidthMode ssoptimizer$getWidthMode();

    @Accessor(value = "widthMultiplier", remap = false)
    float ssoptimizer$getWidthMultiplier();

    /** 游戏原逻辑使组内所有轨迹段的 maxAge 均等于此值。 */
    @Accessor(value = "segmentDuration", remap = false)
    float ssoptimizer$getSegmentDuration();

    @Accessor(value = "key", remap = false)
    Object ssoptimizer$getKey();

    @Invoker(value = "removeExpiredSegment", remap = false)
    boolean ssoptimizer$removeExpiredSegment();
}
