package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.graphics.Sprite;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.combat.entities.G;
import com.fs.starfarer.combat.entities.ship.H;
import com.fs.starfarer.loading.specs.EngineSlot;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 引擎渲染实体（G / Engine）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.G}<br>
 * 注入动机：引擎渲染实体包含所有者引用、颜色偏移器、尺寸偏移器、辉光纹理等渲染所需的
 * 完整状态，原始字段均为混淆名称。优化后的批量渲染管线需要直接访问这些数据。<br>
 * 注入效果：暴露 16 个 Accessor 和 2 个 Invoker。</p>
 */
@Mixin(targets = "com.fs.starfarer.combat.entities.G")
public interface GEngineAccessor {
    @Accessor(value = "Óo0000", remap = false)
    H.Oo ssoptimizer$getOwner();

    @Accessor(value = "õ00000", remap = false)
    boolean ssoptimizer$isSystemActivatedRenderingEnabled();

    @Accessor(value = "öO0000", remap = false)
    ColorShifter ssoptimizer$getColorShifter();

    @Accessor(value = "class", remap = false)
    ValueShifter ssoptimizer$getLengthShifter();

    @Accessor(value = "Oo0000", remap = false)
    ValueShifter ssoptimizer$getWidthShifter();

    @Accessor(value = "Øo0000", remap = false)
    ValueShifter ssoptimizer$getGlowShifter();

    @Accessor(value = "interface", remap = false)
    float ssoptimizer$getColorShiftFraction();

    @Accessor(value = "ØO0000", remap = false)
    boolean ssoptimizer$isBoostedFlameMode();

    @Accessor(value = "null", remap = false)
    Fader ssoptimizer$getPrimaryFader();

    @Accessor(value = "new", remap = false)
    Fader ssoptimizer$getSecondaryFader();

    @Accessor(value = "ÓO0000", remap = false)
    com.fs.graphics.Object ssoptimizer$getPrimaryGlowTexture();

    @Accessor(value = "ÔO0000", remap = false)
    com.fs.graphics.Object ssoptimizer$getSecondaryGlowTexture();

    @Accessor(value = "class.super", remap = false)
    com.fs.graphics.Object ssoptimizer$getFlameTexture();

    @Accessor(value = "oo0000", remap = false)
    Sprite ssoptimizer$getGlowSprite();

    @Invoker(value = "Object", remap = false)
    G.Oo ssoptimizer$getState(EngineSlot slot);

    @Invoker(value = "Õ00000", remap = false)
    void ssoptimizer$renderFighter(float alphaScale);
}