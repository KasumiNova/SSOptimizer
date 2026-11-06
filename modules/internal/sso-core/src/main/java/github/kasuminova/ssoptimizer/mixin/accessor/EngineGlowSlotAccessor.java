package github.kasuminova.ssoptimizer.mixin.accessor;

import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.fs.starfarer.campaign.fleet.CampaignShipEngineGlow$SlotData")
public interface EngineGlowSlotAccessor {
    @Accessor(value = "angle", remap = false)
    float ssoptimizer$getAngle();

    @Accessor(value = "baseLength", remap = false)
    float ssoptimizer$getBaseLength();

    @Accessor(value = "glowSize", remap = false)
    float ssoptimizer$getGlowSize();

    @Accessor(value = "width", remap = false)
    float ssoptimizer$getWidth();

    @Accessor(value = "offset", remap = false)
    Vector2f ssoptimizer$getOffset();
}
