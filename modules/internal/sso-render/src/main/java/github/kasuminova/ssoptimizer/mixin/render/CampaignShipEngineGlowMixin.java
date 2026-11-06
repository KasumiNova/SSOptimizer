package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;
import com.fs.graphics.util.Fader;
import com.fs.profiler.Profiler;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.campaign.fleet.CampaignFleetMemberView;
import com.fs.starfarer.campaign.fleet.CampaignShipEngineGlow;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignEngineGlowRenderHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(CampaignShipEngineGlow.class)
public abstract class CampaignShipEngineGlowMixin {
    @Shadow(remap = false)
    private TextureObject glow;

    @Shadow(remap = false)
    private Sprite hitGlow;

    @Shadow(remap = false)
    private List<CampaignShipEngineGlow.SlotData> slots;

    @Shadow(remap = false)
    private Fader accelFader;

    @Shadow(remap = false)
    private Fader fullFader;

    // XStream 不持久化派生缓存，读档后按槽数重建。
    @Unique
    private transient CampaignEngineGlowRenderHelper.GlowGeometryCache ssoptimizer$glowGeometryCache;

    @Overwrite(remap = false)
    public void render(final CampaignFleetMemberView view, final float facing, final float alphaMult) {
        Profiler.begin("Campaign engine glow");
        final SectorAPI sector = Global.getSector();
        if (this.ssoptimizer$glowGeometryCache == null
                || this.ssoptimizer$glowGeometryCache.slotCount() != this.slots.size()) {
            this.ssoptimizer$glowGeometryCache =
                    new CampaignEngineGlowRenderHelper.GlowGeometryCache(this.slots.size());
        }
        CampaignEngineGlowRenderHelper.render(
                view, this.slots, this.glow, this.hitGlow,
                this.accelFader, this.fullFader, facing, alphaMult,
                view.getFleet(),
                sector == null ? null : sector.getViewport(),
                this.ssoptimizer$glowGeometryCache);
        Profiler.end();
    }
}
