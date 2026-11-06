package github.kasuminova.ssoptimizer.asm.combat;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 在碰撞冲量写入局部槽 16 后拦截 NaN/Inf，命中时跳过速度写入并返回零冲量。
 * 目标局部量位于方法中部且混淆类缺少局部变量表，Mixin 无法捕获，故使用 ASM；
 * {@code FSTORE 16} 锚点必须唯一，否则保留原字节码并记录错误。
 */
public final class CollisionImpulseClampProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = GameClassNames.COLLISION_HANDLER_IMPL;
    public static final String TARGET_METHOD = "applyCollisionImpulse";
    public static final String TARGET_DESC = "(Lcom/fs/starfarer/combat/CollisionEntity;"
            + "Lcom/fs/starfarer/combat/CollisionEntity;Lorg/lwjgl/util/vector/Vector2f;)F";

    private static final Logger LOGGER = Logger.getLogger(CollisionImpulseClampProcessor.class);

    private static final String HELPER_OWNER = "github/kasuminova/ssoptimizer/common/combat/CombatNaNGuard";
    private static final String CLAMP_METHOD = "shouldClampImpulse";
    private static final String CLAMP_DESC = "(FLcom/fs/starfarer/combat/CollisionEntity;"
            + "Lcom/fs/starfarer/combat/CollisionEntity;)Z";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        if (countImpulseStores(reader) != 1) {
            LOGGER.error("[SSOptimizer] 碰撞冲量钳制锚点失配: FSTORE 16 命中数不为 1，"
                    + "applyCollisionImpulse 保持原版字节码");
            return null;
        }

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String sig, final String[] ex) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(desc)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitVarInsn(final int opcode, final int variable) {
                        super.visitVarInsn(opcode, variable);
                        if (opcode != Opcodes.FSTORE || variable != 16) {
                            return;
                        }
                        // 栈空。检查槽 16（冲量标量），坏值则整段跳过速度写入并返回 0F
                        mv.visitVarInsn(Opcodes.FLOAD, 16);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, CLAMP_METHOD, CLAMP_DESC, false);
                        final Label proceed = new Label();
                        mv.visitJumpInsn(Opcodes.IFEQ, proceed);
                        mv.visitInsn(Opcodes.FCONST_0);
                        mv.visitInsn(Opcodes.FRETURN);
                        mv.visitLabel(proceed);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static int countImpulseStores(final ClassReader reader) {
        final int[] count = new int[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String sig, final String[] ex) {
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(final int opcode, final int variable) {
                        if (opcode == Opcodes.FSTORE && variable == 16) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
