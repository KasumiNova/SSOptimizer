package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.common.combat.ai.AiThreadLocals;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * AIUtils 共享静态状态线程本地化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.AIUtils}<br>
 * 注入动机：{@code AIUtils} 以静态字段充当 AI 计算期临时状态，并行化后必须按线程隔离：<br>
 * <ul>
 *   <li>{@code blockingShips}（private static List）—— 舰体残骸遮挡缓存；</li>
 *   <li>{@code aimErrorOffset1/2}（public static float）—— 瞄偏参数；</li>
 *   <li>{@code null}（public static boolean，字节码合法但源码非法的字段名）——
 *       瞄点计算开关（{@code AttackAIModule} 的写入点由
 *       {@link AttackAiModuleNullFlagMixin} 处理）。</li>
 * </ul>
 * 注入效果：上述字段的 GETSTATIC/PUTSTATIC 全部重定向到
 * {@link AiThreadLocals} 的 ThreadLocal 实现。字段声明保留，值恒为默认——
 * 原版同样在计算结束即复位为默认值，语义不受影响。
 */
@Mixin(targets = GameClassNames.AI_UTILS_DOTTED)
public abstract class AiUtilsThreadLocalMixin {
    /** blockingShips 读取 → 线程本地（帧号失效）。 */
    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;blockingShips:Ljava/util/List;"))
    private static List ssoptimizer$getBlockingShips() {
        return AiThreadLocals.getBlockingShips();
    }

    /** blockingShips 写入（含 clear/reset 传 null）→ 线程本地。 */
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

    /** 字段名为 {@code null}（字节码合法）：读取 → 线程本地。 */
    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;null:Z"))
    private static boolean ssoptimizer$getNullFlag() {
        return AiThreadLocals.getNullFlag();
    }

    /** 字段名为 {@code null}（字节码合法）：写入 → 线程本地。 */
    @Redirect(method = "*", remap = false, at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lcom/fs/starfarer/combat/ai/AIUtils;null:Z"))
    private static void ssoptimizer$setNullFlag(boolean value) {
        AiThreadLocals.setNullFlag(value);
    }
}
