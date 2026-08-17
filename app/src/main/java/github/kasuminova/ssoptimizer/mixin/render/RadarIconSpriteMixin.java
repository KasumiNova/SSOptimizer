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

/**
 * 雷达条图标肖像合成的 Mixin 覆写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.CombatRadarHud.RadarIconSprite#renderAtPositions(FFFShipFighterWing)}<br>
 * 注入动机：原版每帧每图标调用 {@link ShipPortraitRenderer#render}，内含
 * stencil+alpha test 区间与约 40 次 GL 状态调用，stencil 使 SpriteBatch 拒绝合批
 * （P3 基准：RadarRibbonIconManager.renderAll 4.8%，肖像合成为大头）。
 * 合成内容对同一图标完全静态，仅透明度逐帧变化。<br>
 * 注入效果：方法体替换为「基础图标照常合批绘制 + 肖像合成改为
 * {@link RadarCompositeCache} 的一次性 FBO 烘焙缓存，逐帧一次 additive 绘制
 * （全部图标共享同一合成纹理与 blend，可跨图标合批）」；缓存不可用/单元格耗尽/
 * display list 编译区间时逐调用回退原版 stencil 路径。
 * 缓存失效条件（图标尺寸/图标色 alpha 变化、单元格被回收）自动触发重烘焙。
 */
@Mixin(targets = GameClassNames.RADAR_ICON_SPRITE_DOTTED)
public abstract class RadarIconSpriteMixin {
    /** 灰度顶点色缓存：h=1（稳态）时复用，避免每帧分配。 */
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

    /** 成员变体合成单元格（-1 = 未分配/已失效）。 */
    @Unique
    private int ssoptimizer$cellMember = -1;

    /** 幽灵位变体合成单元格。 */
    @Unique
    private int ssoptimizer$cellGhost = -1;

    /** 两个变体对应的缓存绘制 sprite。 */
    @Unique
    private Sprite ssoptimizer$compositeMember;

    @Unique
    private Sprite ssoptimizer$compositeGhost;

    /** 上次烘焙时的图标尺寸与图标色 alpha（变化则重烘焙）。 */
    @Unique
    private float ssoptimizer$bakedWidth = -1.0F;

    @Unique
    private float ssoptimizer$bakedHeight = -1.0F;

    @Unique
    private int ssoptimizer$bakedIconAlpha = -1;

    @Unique
    private final float[] ssoptimizer$uvBuf = new float[4];

    /**
     * @author KasumiNova
     * @reason 原版逐帧 stencil 合成改为 FBO 烘焙缓存 + 单次 additive 合批绘制。
     */
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

    /**
     * 绘制图标的肖像合成层：缓存命中时一次 additive sprite 绘制，否则回退原版。
     *
     * @param x        图标中心 X
     * @param y        图标中心 Y
     * @param h        逐帧透明度（fader × 距离淡出）
     * @param isMember 真实成员（叠加聚光灯层）或幽灵位
     */
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
            // 单元格耗尽：回退原版 stencil 路径
            this.renderer.render(this.sprite, x, y, h, isMember);
            return;
        }
        // 原版剪影 alpha 含 h² 项：烘焙以 h=1 进行，逐帧以灰度顶点色 h² 调制
        final int v = Math.min(255, Math.max(0, (int) (h * h * 255.0F + 0.5F)));
        composite.setColor(v >= 255 ? ssoptimizer$WHITE : new Color(v, v, v));
        composite.renderAtCenter(x, y);
    }

    /** 校验缓存有效性（触碰续期 + 尺寸/颜色漂移检测），必要时重新分配并烘焙两个变体。 */
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

    /** 分配单元格并烘焙一个变体；返回单元格编号（耗尽 -1）。 */
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

    /** 为单元格内容区构建缓存绘制 sprite（additive，UV 指向格内居中裁剪区）。 */
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
