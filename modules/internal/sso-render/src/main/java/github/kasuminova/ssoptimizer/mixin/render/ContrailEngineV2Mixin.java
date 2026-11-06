package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignContrailAdvanceHelper;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignContrailBatchHelper;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignFleetPerformanceHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(ContrailEngineV2.class)
public abstract class ContrailEngineV2Mixin {
    @Shadow(remap = false)
    private Map<Object, ContrailEngineV2.Contrail> contrails;

    @Overwrite(remap = false)
    public void render(float alphaMult) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        CampaignContrailBatchHelper.renderContrails(contrails, alphaMult);
    }

    @Overwrite(remap = false)
    public void advance(float amount) {
        CampaignContrailAdvanceHelper.advance(contrails, amount);
    }

    @Inject(method = "addPoint", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$capContrailPoints(final Object source,
                                               final Vector2f point,
                                               final Vector2f vel,
                                               final float brightness,
                                               final CallbackInfo ci) {
        if (CampaignFleetPerformanceHelper.isContrailPointCapReached(
                contrails.get(source), CampaignFleetPerformanceHelper.CONTRAIL_MAX_POINTS)) {
            ci.cancel();
        }
    }
}
