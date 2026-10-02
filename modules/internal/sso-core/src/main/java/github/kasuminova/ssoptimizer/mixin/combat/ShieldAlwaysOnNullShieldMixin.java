package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import github.kasuminova.ssoptimizer.common.combat.ShieldAlwaysOnGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code ShieldAlwaysOn} hullmod 无盾判空 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.impl.hullmods.ShieldAlwaysOn#advanceInCombat}
 * 内唯一的 {@code ShieldAPI.isOn()} 调用点。<br>
 * 注入动机：原版对 {@code ship.getShield()} 不判空，模组将该 hullmod 配在
 * 无盾/相位船体上时（盾实体不会被 {@code ShipFactory} 创建），舰船部署后首次
 * advance 即 NPE（实机堆栈：lwe 模组无人机 spawnFleetMember → deployMember →
 * Ship.advance → ShieldAlwaysOn.advanceInCombat）。<br>
 * 注入效果：重定向 {@code isOn()} 调用至 {@link ShieldAlwaysOnGuard}——有盾时
 * 原样委托；无盾时视为「已开启」跳过 {@code toggleOn()}，方法其余逻辑
 * （禁用盾/相位切换命令、超载与辐能修正）保持原版行为，并按舰船实例 WARN 一次。
 */
@Mixin(targets = GameClassNames.SHIELD_ALWAYS_ON_DOTTED, remap = false)
public abstract class ShieldAlwaysOnNullShieldMixin {
    /**
     * 重定向 advanceInCombat 内的 ShieldAPI.isOn() 调用（方法内唯一锚点）。
     *
     * @param shield 盾实例（原调用接收者，可为 null）
     * @param ship 所属舰船（advanceInCombat 参数）
     * @param amount 推进时长（advanceInCombat 参数，未使用）
     * @return 空安全盾开启状态
     * @author KasumiNova
     * @reason 原版不判空，模组误配无盾船体时部署即 NPE。
     */
    @Redirect(
            method = "advanceInCombat(Lcom/fs/starfarer/api/combat/ShipAPI;F)V",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/api/combat/ShieldAPI;isOn()Z"),
            require = 1,
            remap = false)
    private boolean ssoptimizer$nullShieldSafeIsOn(final ShieldAPI shield,
                                                   final ShipAPI ship,
                                                   final float amount) {
        return ShieldAlwaysOnGuard.isShieldOnOrAbsent(shield, ship);
    }
}
