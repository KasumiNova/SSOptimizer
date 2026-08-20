package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CharacterStats 读档刷新批处理 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.CharacterStats}<br>
 * 注入动机：读档时 {@code readResolve} 会为每个缺失的 APTITUDE 调用
 * {@code setAptitudeLevel} → {@code setSkillLevel}，而每次 {@code setSkillLevel}
 * 都会触发一次 {@code refreshCharacterStatsEffects} 全量重算（先 unapply 全部技能效果
 * 再 apply 全部），随后 readResolve 末尾还要再做一次全量刷新。对技能全满的角色
 * 这意味着数十次完全冗余的全量重算，JFR 实测占 unmarshal 窗口约 40%，
 * 且会连带触发模组技能脚本的类加载与 unapply 逻辑。<br>
 * 注入效果：readResolve 期间置位游戏自带的 {@code skipRefresh} 批处理标志
 * （构造器已在用同一模式），返回前复位并执行一次全量刷新。
 * 由于 refreshCharacterStatsEffects 是全量重算语义，中间态刷新不产生任何外部可见效果，
 * 最终状态与原版逐次刷新完全一致。
 */
@Mixin(targets = GameClassNames.CHARACTER_STATS_DOTTED, remap = false)
public abstract class CharacterStatsReadResolveBatchMixin {
    @Shadow
    private boolean skipRefresh;

    @Shadow
    public abstract void refreshCharacterStatsEffects();

    /**
     * readResolve 进入时开启批处理，抑制途中所有中间态全量刷新。
     */
    @Inject(method = "readResolve", at = @At("HEAD"), remap = false)
    private void ssoptimizer$batchRefreshBegin(final CallbackInfoReturnable<Object> ci) {
        skipRefresh = true;
    }

    /**
     * readResolve 返回前复位标志并执行唯一一次全量刷新，
     * 替代原版末尾的 refreshCharacterStatsEffects 调用（该调用在标志抑制下为空操作）。
     */
    @Inject(method = "readResolve", at = @At("RETURN"), remap = false)
    private void ssoptimizer$batchRefreshEnd(final CallbackInfoReturnable<Object> ci) {
        skipRefresh = false;
        refreshCharacterStatsEffects();
    }
}
