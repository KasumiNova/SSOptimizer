package github.kasuminova.ssoptimizer.mixin.modopt.sotf;

import com.fs.starfarer.api.combat.ShipAPI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SotF 昼梦合成器（Daydream Synthesizer）递归复制修复。
 * <p>
 * 注入目标：{@code data.hullmods.SotfDaydreamSynthesizer}
 * （外部模组 Secrets of the Frontier / 边境秘闻；模组不存在时 Mixin 记 WARN 跳过）。<br>
 * 注入动机：{@code spawnMimic} 有意在复制品变体上保留昼梦合成器插件（原作者注释：让插件占用复制品的装配点），
 * 而 {@code advanceInCombat} 的一次性门控 {@code ALREADY_SYNTHESIZED_KEY} 以舰船实例 id 为键——
 * 复制品是新实例、键不命中，部署 2 秒后再次触发合成，合成体仍带插件，形成无界递归复制
 * （实机表现：全图持续刷出 "DEPLOYING COUNTERMEASURE" 文本与大量复制舰，帧率雪崩）。<br>
 * 注入效果：{@code advanceInCombat} 方法头检查目标是否已是纳米机群合成体
 * （{@code sotf_nanite_synthesized} 插件），是则直接返回——复制品不再触发二次合成，
 * 原始舰船（玩家装配该插件的舰船）行为完全不变。<br>
 */
@Mixin(targets = "data.hullmods.SotfDaydreamSynthesizer")
public abstract class DaydreamSynthesizerMixin {

    /**
     * 复制品二次合成的拦截。
     *
     * @param ship 当前触发插件的舰船
     * @param amount 帧间隔（秒）
     * @param ci 回调（目标为合成体时取消原方法）
     * @reason 模组的一次性门控以实例 id 为键，覆盖不到复制品自身
     */
    @Inject(method = "advanceInCombat", at = @At("HEAD"), cancellable = true, remap = false)
    private void sso$skipMimicSynthesis(final ShipAPI ship, final float amount, final CallbackInfo ci) {
        if (ship.getVariant().hasHullMod("sotf_nanite_synthesized")) {
            ci.cancel();
        }
    }
}
