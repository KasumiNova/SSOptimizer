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

@Mixin(targets = GameMixinSignatures.MutableStat.TARGET_CLASS)
public abstract class MutableStatMutationMixin implements StatMutationBridge {
    @Shadow
    private transient boolean needsRecompute;

    /** 代际不随存档持久化，读档后由消费者强制刷新并重建缓存。 */
    @Unique
    private transient int ssoptimizer$mutationGeneration;

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
