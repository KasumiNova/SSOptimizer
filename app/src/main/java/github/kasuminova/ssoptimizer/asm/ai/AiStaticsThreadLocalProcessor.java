package github.kasuminova.ssoptimizer.asm.ai;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.objectweb.asm.*;

/**
 * AI 共享静态状态线程本地化的 ASM 处理器。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.AIUtils} 与
 * {@code com.fs.starfarer.combat.ai.attack.AttackAIModule}<br>
 * 注入动机：{@code AIUtils} 以静态字段充当 AI 计算期临时状态，并行化后必须按线程隔离：<br>
 * <ul>
 *   <li>{@code blockingShips}（private static List）—— 舰体残骸遮挡缓存；</li>
 *   <li>{@code aimErrorOffset1/2}（public static float）—— 瞄偏参数；</li>
 *   <li>{@code null}（public static boolean，字节码合法但源码非法的字段名）——
 *       由 {@code AttackAIModule} 在 {@code getAimPoint} 调用前后置位/复位的开关。</li>
 * </ul>
 * 注入效果：上述字段的 GETSTATIC/PUTSTATIC 全部重定向到
 * {@link github.kasuminova.ssoptimizer.common.combat.ai.AiThreadLocals} 的对应静态方法。
 * 字段声明保留（公开字段可能被模组读取，值恒为默认，语义不受影响——原版同样是
 * 计算结束即复位为默认值）。
 */
public final class AiStaticsThreadLocalProcessor implements AsmClassProcessor {
    private static final Logger LOGGER = Logger.getLogger(AiStaticsThreadLocalProcessor.class);

    private static final String AI_UTILS       = GameClassNames.AI_UTILS;
    private static final String ATTACK_MODULE  = GameClassNames.ATTACK_AI_MODULE;
    private static final String LOCALS_OWNER   = "github/kasuminova/ssoptimizer/common/combat/ai/AiThreadLocals";

    /** 字段名 → [getter, setter, 描述符]。注意 "null" 是字节码层面的合法字段名。 */
    private static final String[][] REDIRECTS = {
            {"blockingShips",   "getBlockingShips",   "setBlockingShips",   "Ljava/util/List;"},
            {"aimErrorOffset1", "getAimErrorOffset1", "setAimErrorOffset1", "F"},
            {"aimErrorOffset2", "getAimErrorOffset2", "setAimErrorOffset2", "F"},
            {"null",            "getNullFlag",        "setNullFlag",        "Z"},
    };

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        String className = reader.getClassName();
        if (!AI_UTILS.equals(className) && !ATTACK_MODULE.equals(className)) {
            return null;
        }

        final int[] redirected = {0};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                        // AttackAIModule 只重定向 AIUtils.null 开关；AIUtils 重定向全部四项
                        boolean attackModule = ATTACK_MODULE.equals(className);
                        for (String[] redirect : REDIRECTS) {
                            if (attackModule && !"null".equals(redirect[0])) {
                                continue;
                            }
                            if (AI_UTILS.equals(owner) && redirect[0].equals(fieldName) && redirect[3].equals(fieldDesc)) {
                                if (opcode == Opcodes.GETSTATIC) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, LOCALS_OWNER, redirect[1], "()" + fieldDesc, false);
                                    redirected[0]++;
                                    return;
                                }
                                if (opcode == Opcodes.PUTSTATIC) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, LOCALS_OWNER, redirect[2], "(" + fieldDesc + ")V", false);
                                    redirected[0]++;
                                    return;
                                }
                            }
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
                    }
                };
            }
        }, 0);

        if (redirected[0] == 0) {
            LOGGER.warn("[SSOptimizer] AiStaticsThreadLocalProcessor redirected nothing in " + className);
            return null;
        }
        LOGGER.info("[SSOptimizer] Thread-localized " + redirected[0] + " AI static access site(s) in " + className);
        return writer.toByteArray();
    }
}
