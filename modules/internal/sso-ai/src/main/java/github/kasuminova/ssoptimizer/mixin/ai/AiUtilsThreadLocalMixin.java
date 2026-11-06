package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.common.combat.ai.AiThreadLocals;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * 将 {@code AIUtils} 的静态临时字段重定向到 {@link AiThreadLocals}，隔离并行工作线程。
 * 名为 {@code null} 的字段仅存在于字节码中，其读写也必须使用线程本地状态。
 */
@Mixin(targets = GameClassNames.AI_UTILS_DOTTED)
public abstract class AiUtilsThreadLocalMixin {
    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;blockingShips:Ljava/util/List;"))
    private static List ssoptimizer$getBlockingShips() {
        return AiThreadLocals.getBlockingShips();
    }

    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;blockingShips:Ljava/util/List;"))
    private static void ssoptimizer$setBlockingShips(List value) {
        AiThreadLocals.setBlockingShips(value);
    }

    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;aimErrorOffset1:F"))
    private static float ssoptimizer$getAimErrorOffset1() {
        return AiThreadLocals.getAimErrorOffset1();
    }

    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;aimErrorOffset1:F"))
    private static void ssoptimizer$setAimErrorOffset1(float value) {
        AiThreadLocals.setAimErrorOffset1(value);
    }

    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;aimErrorOffset2:F"))
    private static float ssoptimizer$getAimErrorOffset2() {
        return AiThreadLocals.getAimErrorOffset2();
    }

    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;aimErrorOffset2:F"))
    private static void ssoptimizer$setAimErrorOffset2(float value) {
        AiThreadLocals.setAimErrorOffset2(value);
    }

    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;null:Z"))
    private static boolean ssoptimizer$getNullFlag() {
        return AiThreadLocals.getNullFlag();
    }

    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;null:Z"))
    private static void ssoptimizer$setNullFlag(boolean value) {
        AiThreadLocals.setNullFlag(value);
    }
}
