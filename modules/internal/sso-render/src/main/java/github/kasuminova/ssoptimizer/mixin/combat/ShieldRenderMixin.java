package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.graphics.TextureObject;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.entities.ship.trackers.AimTracker;
import com.fs.starfarer.combat.entities.ship.trackers.ShieldWeaponTracker;
import com.fs.starfarer.combat.systems.OffsetPoint;
import github.kasuminova.ssoptimizer.common.render.shield.ShieldRenderHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.awt.*;

@Mixin(targets = GameClassNames.SHIELD_DOTTED)
public abstract class ShieldRenderMixin {
    @Shadow(remap = false)
    private boolean skipRendering;

    @Shadow(remap = false)
    private float arc;

    @Shadow(remap = false)
    private float radius;

    @Shadow(remap = false)
    private int segmentCount;

    @Shadow(remap = false)
    private float[] segmentAlpha;

    @Shadow(remap = false)
    private float segmentAlphaMax;

    @Shadow(remap = false)
    private Color innerColor;

    @Shadow(remap = false)
    private Color ringColor;

    @Shadow(remap = false)
    private float ringAngle;

    @Shadow(remap = false)
    private float innerAngle;

    @Shadow(remap = false)
    private float textureScale;

    @Shadow(remap = false)
    private Fader effectFader;

    @Shadow(remap = false)
    private float effectStrength;

    @Shadow(remap = false)
    private float effectSizeMult;

    @Shadow(remap = false)
    private float effectRadiusMult;

    @Shadow(remap = false)
    private boolean renderAdditive;

    @Shadow(remap = false)
    private Ship ship;

    @Shadow(remap = false)
    private ShieldWeaponTracker chargeTracker;

    @Shadow(remap = false)
    private AimTracker aimTracker;

    @Shadow(remap = false)
    private OffsetPoint offsetPoint;

    @Shadow(remap = false)
    private TextureObject innerTexture;

    @Shadow(remap = false)
    private TextureObject bandTexture;

    @Unique
    private final ShieldRenderHelper.Params ssoptimizer$renderParams = new ShieldRenderHelper.Params();

    @Overwrite(remap = false)
    public void render(float amount) {
        if (this.skipRendering) {
            return;
        }

        Vector2f offset = this.offsetPoint.computeLocation(this.ship);
        Vector2f shipLoc = this.ship.getLocation();

        ShieldRenderHelper.Params p = this.ssoptimizer$renderParams;
        p.amount = amount;
        p.arc = this.arc;
        p.radius = this.radius;
        p.segmentCount = this.segmentCount;
        p.segmentAlpha = this.segmentAlpha;
        p.segmentAlphaMax = this.segmentAlphaMax;
        p.innerColor = this.innerColor;
        p.ringColor = this.ringColor;
        p.ringAngle = this.ringAngle;
        p.innerAngle = this.innerAngle;
        p.textureScale = this.textureScale;
        p.renderAdditive = this.renderAdditive;
        p.shipFighter = this.ship.isFighter();
        p.shipFrigate = this.ship.isFrigate();
        p.offsetX = offset.x - shipLoc.x;
        p.offsetY = offset.y - shipLoc.y;
        p.facing = this.aimTracker.getFacing();
        p.chargeLevel = this.chargeTracker.getChargeLevel();
        p.chargeDamageMult = this.chargeTracker.getDamageMult();
        p.effectBrightness = this.effectFader != null ? this.effectFader.getBrightness() : 0.0F;
        p.effectStrength = this.effectStrength;
        p.effectSizeMult = this.effectSizeMult;
        p.effectRadiusMult = this.effectRadiusMult;
        p.innerTexture = this.innerTexture;
        p.bandTexture = this.bandTexture;

        ShieldRenderHelper.render(p);
    }
}
