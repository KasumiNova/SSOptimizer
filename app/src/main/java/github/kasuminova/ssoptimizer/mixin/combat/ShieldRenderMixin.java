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

/**
 * 舰船护盾（Shield）渲染方法的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.systems.Shield#render(float)}<br>
 * 注入动机：原版护盾渲染为两遍立即模式 TRIANGLE_FAN + 一遍 QUAD_STRIP，
 * 360° 盾单帧约 800 次三角函数与 800 次立即模式 JNI 调用，是帧时间热点。<br>
 * 注入效果：整体委托 {@link ShieldRenderHelper}，以旋转递推 + 顶点缓存 + 数组合批替换立即模式；
 * 优化关停或显示列表编译期间由 helper 回退到逐行复刻原版的立即模式路径。<br>
 * 可见性说明：{@code ShieldWeaponTracker} / {@code AimTracker} / {@code OffsetPoint}
 * 均为 public 类且所需方法 public，可直接 Shadow 并按需取 primitive 值传入 helper，
 * 无需 accessor。原版私有方法 {@code getSegmentBrightness(int)} 为单行公式
 * （1 − 0.45·segmentAlpha/segmentAlphaMax），由
 * {@link github.kasuminova.ssoptimizer.common.render.shield.ShieldArcGeometry#segmentBrightness} 复刻。
 */
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

    /**
     * 将护盾渲染整体委托给 {@link ShieldRenderHelper}。
     *
     * @param amount 帧时间参数
     * @author KasumiNova
     * @reason 原版立即模式绘制是帧时间热点，替换为递推顶点生成 + 数组合批路径；
     * 关停或显示列表编译期间由 helper 内部回退到等价立即模式。
     */
    @Overwrite(remap = false)
    public void render(float amount) {
        // 原版：if (!this.skipRendering) { ... }
        if (this.skipRendering) {
            return;
        }

        // 原版：var4 = offsetPoint.computeLocation(ship); var4 -= ship.getLocation()
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
