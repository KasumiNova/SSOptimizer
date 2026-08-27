package github.kasuminova.ssoptimizer.mixin.accessor;

import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 战役舰队引擎辉光槽位（{@code CampaignShipEngineGlow$SlotData}）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.starfarer.campaign.fleet.CampaignShipEngineGlow$SlotData}<br>
 * 注入动机：SlotData 的全部字段为包私有，战役引擎辉光合批渲染
 * （{@code CampaignEngineGlowRenderHelper}）需要直接读取角度、长度、宽度、辉光尺寸
 * 与槽位偏移来做无分配的批量顶点编码。<br>
 * 注入效果：暴露 5 个只读 Accessor，供合批 helper 与单测的等价实现复用。</p>
 */
@Mixin(targets = "com.fs.starfarer.campaign.fleet.CampaignShipEngineGlow$SlotData")
public interface EngineGlowSlotAccessor {
    /** 引擎槽朝向角（度），辉光条沿 {@code angle - 90} 方向延伸。 */
    @Accessor(value = "angle", remap = false)
    float ssoptimizer$getAngle();

    /** 辉光条基准长度（构造期已乘 scaleMult）。 */
    @Accessor(value = "baseLength", remap = false)
    float ssoptimizer$getBaseLength();

    /** hitGlow 精灵基准尺寸（构造期已乘 scaleMult 与舰种系数）。 */
    @Accessor(value = "glowSize", remap = false)
    float ssoptimizer$getGlowSize();

    /** 引擎槽辉光宽度（构造期已乘 scaleMult，下限 1）。 */
    @Accessor(value = "width", remap = false)
    float ssoptimizer$getWidth();

    /** 槽位在船体本地坐标系中的偏移（构造期已乘 scaleMult）。 */
    @Accessor(value = "offset", remap = false)
    Vector2f ssoptimizer$getOffset();
}
