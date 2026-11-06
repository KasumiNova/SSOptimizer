package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.Sprite;
import com.fs.graphics.util.GLListManager;
import com.fs.profiler.Profiler;
import com.fs.starfarer.combat.ai.FighterWing;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.renderers.ShipPortraitRenderer;
import github.kasuminova.ssoptimizer.common.render.hud.RadarCompositeCache;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.awt.Color;

@Mixin(targets = GameClassNames.RADAR_ICON_SPRITE_DOTTED)
public abstract class RadarIconSpriteMixin {
    @Unique
    private static final Color ssoptimizer$WHITE = new Color(255, 255, 255);

    @Shadow(remap = false)
    private Sprite sprite;

    @Shadow(remap = false)
    private boolean multiPosition;

    @Shadow(remap = false)
    private int memberCount;

    @Shadow(remap = false)
    private float formationScale;

    @Shadow(remap = false)
    private float[] positions;

    @Shadow(remap = false)
    private float scale;

    @Shadow(remap = false)
    private Color iconColor;

    @Shadow(remap = false)
    private ShipPortraitRenderer renderer;

    @Unique
    private int ssoptimizer$cellMember = -1;

    @Unique
    private int ssoptimizer$cellGhost = -1;

    @Unique
    private Sprite ssoptimizer$compositeMember;

    @Unique
    private Sprite ssoptimizer$compositeGhost;

    @Unique
    private float ssoptimizer$bakedWidth = -1.0F;

    @Unique
    private float ssoptimizer$bakedHeight = -1.0F;

    @Unique
    private int ssoptimizer$bakedIconAlpha = -1;

    @Unique
    private final float[] ssoptimizer$uvBuf = new float[4];

    @Overwrite(remap = false)
    private void renderAtPositions(float f, float g, float h, Ship arg, FighterWing arg2) {
        this.sprite.setColor(this.iconColor);
        if (this.multiPosition) {
            final float formationScale = this.formationScale * this.scale;
            final int slotCount = this.positions.length / 2;
            for (int slot = 0; slot < slotCount; slot++) {
                final boolean isMember = slot < this.memberCount;
                float alphaScale = 1.0F;
                if (isMember) {
                    this.sprite.setAlphaMult(0.4F * h);
                } else {
                    this.sprite.setAlphaMult(0.2F * h);
                    alphaScale = 0.5F;
                }
                final float x = f + this.positions[slot * 2] * formationScale;
                final float y = g + this.positions[slot * 2 + 1] * formationScale;
                this.sprite.renderAtCenter(x, y);
                Profiler.begin("ISR");
                ssoptimizer$renderComposite(x, y, h * alphaScale, isMember);
                Profiler.end();
            }
        } else {
            this.sprite.setAlphaMult(0.4F * h);
            this.sprite.setBlendFunc(770, 771);
            this.sprite.renderAtCenter(f, g);
            Profiler.begin("ISR");
            ssoptimizer$renderComposite(f, g, h, true);
            Profiler.end();
        }
    }

    @Unique
    private void ssoptimizer$renderComposite(final float x, final float y, final float h,
                                             final boolean isMember) {
        final RadarCompositeCache cache = RadarCompositeCache.getInstance();
        if (!cache.isAvailable() || GLListManager.buildingList) {
            this.renderer.render(this.sprite, x, y, h, isMember);
            return;
        }
        ssoptimizer$ensureBaked(cache, y);
        final Sprite composite = isMember ? this.ssoptimizer$compositeMember : this.ssoptimizer$compositeGhost;
        if (composite == null) {
            this.renderer.render(this.sprite, x, y, h, isMember);
            return;
        }
        // 烘焙使用 h=1，绘制时以灰度顶点色应用 h²。
        final int v = Math.min(255, Math.max(0, (int) (h * h * 255.0F + 0.5F)));
        composite.setColor(v >= 255 ? ssoptimizer$WHITE : new Color(v, v, v));
        composite.renderAtCenter(x, y);
    }

    @Unique
    private void ssoptimizer$ensureBaked(final RadarCompositeCache cache, final float y) {
        final float w = this.sprite.getWidth();
        final float h = this.sprite.getHeight();
        final int iconAlpha = this.iconColor.getAlpha();
        final boolean memberAlive = cache.touchCell(this.ssoptimizer$cellMember, this);
        final boolean ghostAlive = cache.touchCell(this.ssoptimizer$cellGhost, this);
        if (memberAlive && ghostAlive
                && w == this.ssoptimizer$bakedWidth && h == this.ssoptimizer$bakedHeight
                && iconAlpha == this.ssoptimizer$bakedIconAlpha) {
            return;
        }
        if (!memberAlive) {
            this.ssoptimizer$cellMember = -1;
        }
        if (!ghostAlive) {
            this.ssoptimizer$cellGhost = -1;
        }

        final float gridShiftY = (int) y - y;
        this.ssoptimizer$cellMember = ssoptimizer$bakeVariant(cache, true, gridShiftY);
        this.ssoptimizer$compositeMember = this.ssoptimizer$cellMember >= 0
                ? ssoptimizer$compositeSprite(cache, this.ssoptimizer$cellMember) : null;
        this.ssoptimizer$cellGhost = ssoptimizer$bakeVariant(cache, false, gridShiftY);
        this.ssoptimizer$compositeGhost = this.ssoptimizer$cellGhost >= 0
                ? ssoptimizer$compositeSprite(cache, this.ssoptimizer$cellGhost) : null;
        this.ssoptimizer$bakedWidth = w;
        this.ssoptimizer$bakedHeight = h;
        this.ssoptimizer$bakedIconAlpha = iconAlpha;
    }

    @Unique
    private int ssoptimizer$bakeVariant(final RadarCompositeCache cache, final boolean withSpotlight,
                                        final float gridShiftY) {
        final int cell = cache.acquireCell(this);
        if (cell < 0) {
            return -1;
        }
        cache.bakeCell(cell, this.sprite, withSpotlight, gridShiftY);
        return cell;
    }

    @Unique
    private Sprite ssoptimizer$compositeSprite(final RadarCompositeCache cache, final int cell) {
        final Sprite composite = new Sprite(cache.compositeTexture());
        final int packed = cache.cellContentUv(cell, this.ssoptimizer$uvBuf);
        composite.setTexX(this.ssoptimizer$uvBuf[0]);
        composite.setTexY(this.ssoptimizer$uvBuf[1]);
        composite.setTexWidth(this.ssoptimizer$uvBuf[2]);
        composite.setTexHeight(this.ssoptimizer$uvBuf[3]);
        composite.setSize(packed >> 16, packed & 0xFFFF);
        composite.setBlendFunc(1, 1);
        return composite;
    }
}
