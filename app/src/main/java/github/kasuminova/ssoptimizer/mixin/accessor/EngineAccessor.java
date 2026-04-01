package github.kasuminova.ssoptimizer.mixin.accessor;

import com.fs.graphics.Sprite;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.combat.entities.EngineState;
import com.fs.starfarer.combat.entities.ship.EngineOwner;
import com.fs.starfarer.loading.specs.EngineSlot;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 引擎渲染实体（Engine）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.entities.Engine}<br>
 * 注入动机：引擎渲染实体包含所有者引用、颜色偏移器、尺寸偏移器、辉光纹理等渲染所需的
 * 完整状态，原始字段均为混淆名称。优化后的批量渲染管线需要直接访问这些数据。<br>
 * 注入效果：暴露 16 个 Accessor 和 2 个 Invoker。</p>
 */
@Mixin(targets = GameClassNames.ENGINE_DOTTED)
public interface EngineAccessor {
    @Accessor(value = "owner", remap = false)
    EngineOwner ssoptimizer$getOwner();

    @Accessor(value = "systemActivatedRenderingEnabled", remap = false)
    boolean ssoptimizer$isSystemActivatedRenderingEnabled();

    @Accessor(value = "colorShifter", remap = false)
    ColorShifter ssoptimizer$getColorShifter();

    @Accessor(value = "lengthShifter", remap = false)
    ValueShifter ssoptimizer$getLengthShifter();

    @Accessor(value = "widthShifter", remap = false)
    ValueShifter ssoptimizer$getWidthShifter();

    @Accessor(value = "glowShifter", remap = false)
    ValueShifter ssoptimizer$getGlowShifter();

    @Accessor(value = "colorShiftFraction", remap = false)
    float ssoptimizer$getColorShiftFraction();

    @Accessor(value = "boostedFlameMode", remap = false)
    boolean ssoptimizer$isBoostedFlameMode();

    @Accessor(value = "primaryFader", remap = false)
    Fader ssoptimizer$getPrimaryFader();

    @Accessor(value = "secondaryFader", remap = false)
    Fader ssoptimizer$getSecondaryFader();

    @Accessor(value = "primaryGlowTexture", remap = false)
    com.fs.graphics.TextureObject ssoptimizer$getPrimaryGlowTexture();

    @Accessor(value = "secondaryGlowTexture", remap = false)
    com.fs.graphics.TextureObject ssoptimizer$getSecondaryGlowTexture();

    @Accessor(value = "flameTexture", remap = false)
    com.fs.graphics.TextureObject ssoptimizer$getFlameTexture();

    @Accessor(value = "glowSprite", remap = false)
    Sprite ssoptimizer$getGlowSprite();

    @Invoker(value = "getState", remap = false)
    EngineState ssoptimizer$getState(EngineSlot slot);

    @Invoker(value = "renderFighter", remap = false)
    void ssoptimizer$renderFighter(float alphaScale);
}