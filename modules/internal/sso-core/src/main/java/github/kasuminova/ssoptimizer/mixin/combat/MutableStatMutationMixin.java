package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.api.combat.MutableStat;
import github.kasuminova.ssoptimizer.common.combat.StatMutationBridge;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code MutableStat} 修改代际计数 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.combat.MutableStat}<br>
 * 注入动机：市场商品事件修正的签名化置脏需要以 O(1) 代价检测 {@code available}
 * 统计的真实修改（子市场/事件代码每帧可能 {@code modifyFlat}/{@code unmodifyFlat}），
 * 直接读 {@code getModifiedValue()} 会在每帧置脏的统计上触发全量重算。<br>
 * 注入效果：重定向全部 {@code needsRecompute = true} 赋值点——这些点恰好是
 * 「真实改变计算结果」的写路径（同值覆写走不到 PUTAFIELD），在写字段的同时递增
 * 代际计数器；同值覆写、无目标移除、temp mod 时间递减均不计数。
 */
@Mixin(targets = GameMixinSignatures.MutableStat.TARGET_CLASS)
public abstract class MutableStatMutationMixin implements StatMutationBridge {
    @Shadow
    private transient boolean needsRecompute;

    /**
     * 修改代际计数器。transient：{@code MutableStat} 会被战役存档 XStream
     * 序列化（原版 {@code needsRecompute} 同样标 transient）；读档后归零，
     * 与 {@code CommodityOnMarketMixin} 的签名缓存（同为 transient）一并重建，
     * 首次市场推进强制置脏刷新一次，无漏刷新风险。
     */
    @Unique
    private transient int ssoptimizer$mutationGeneration;

    /**
     * 拦截 {@code needsRecompute = true} 的字段写入并递增代际。
     * <p>
     * 目标方法内的 PUTAFIELD owner 恒为 {@code this}（均为 {@code this.needsRecompute = ...}），
     * 故直接经 {@link #needsRecompute} 影子字段完成原始写入。<br>
     * method 列表为字节码中全部含 {@code needsRecompute = true} 赋值的方法
     * （{@code recompute()} 写 false、二参 modify* 纯委托，均不在列）；
     * 数组字面量必须内联于此（注解参数要求编译期常量，数组无常量表达式），
     * 完备性由 sso-app 的 MutableStatMutationAnchorTest 从本注解回读校验。
     */
    @Redirect(
            method = {
                    "readResolve()Ljava/lang/Object;",
                    "applyMods(Lcom/fs/starfarer/api/combat/MutableStat;)V",
                    "applyMods(Lcom/fs/starfarer/api/combat/StatBonus;)V",
                    "modifyFlat(Ljava/lang/String;FLjava/lang/String;)V",
                    "modifyPercent(Ljava/lang/String;FLjava/lang/String;)V",
                    "modifyPercentAlways(Ljava/lang/String;FLjava/lang/String;)V",
                    "modifyMult(Ljava/lang/String;FLjava/lang/String;)V",
                    "modifyMultAlways(Ljava/lang/String;FLjava/lang/String;)V",
                    "modifyFlatAlways(Ljava/lang/String;FLjava/lang/String;)V",
                    "unmodify()V",
                    "unmodify(Ljava/lang/String;)V",
                    "unmodifyFlat(Ljava/lang/String;)V",
                    "unmodifyPercent(Ljava/lang/String;)V",
                    "unmodifyMult(Ljava/lang/String;)V",
                    "setBaseValue(F)V"
            },
            at = @At(
                    value = "FIELD",
                    target = GameMixinSignatures.MutableStat.NEEDS_RECOMPUTE_FIELD,
                    opcode = Opcodes.PUTFIELD),
            remap = false)
    private void ssoptimizer$trackMutation(final MutableStat owner, final boolean value) {
        needsRecompute = value;
        if (value) {
            ssoptimizer$mutationGeneration++;
        }
    }

    @Override
    public int ssoptimizer$getMutationGeneration() {
        return ssoptimizer$mutationGeneration;
    }
}
