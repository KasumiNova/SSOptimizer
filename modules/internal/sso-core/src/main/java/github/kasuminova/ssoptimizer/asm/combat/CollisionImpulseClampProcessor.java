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
 * 碰撞冲量 NaN/Inf 钳制的 ASM 处理器。
 * <p>
 * 注入目标：{@code CollisionHandlerImpl.applyCollisionImpulse(CollisionEntity, CollisionEntity, Vector2f)F}。<br>
 * <b>为什么不用 Mixin</b>：钳制点是方法中部的冲量标量（局部槽 16）——它在双方速度写入
 * （{@code putfield Vector2f.x/y}）之前才算出，必须读中段局部槽；混淆游戏 jar 的
 * LocalVariableTable 只保留方法参数（javap 坐实），Mixin locals capture 无表可依，
 * 项目亦无 MixinExtras。HEAD 检查（现有 {@code CollisionImpulseNaNTraceMixin}）只能看到
 * 入参，看不到方法内部经点积/质量除法放大产生的 Inf/NaN（有限大值点积可溢出为 Inf，
 * Inf/Inf 即 NaN）。<br>
 * 注入效果：在 {@code FSTORE 16}（原版 bytecode 221，方法内唯一，首次速度写在 bytecode 235
 * 之后）之后检查槽 16；NaN/Inf 时按「无冲量」降级——跳过双方速度与角速度写入、返回 0F
 * （调用方据此算出的碰撞伤害归零），钳 0 不钳 {@code Float.MAX_VALUE}。<br>
 * 锚点失配防护：预扫描要求 {@code FSTORE 16} 在目标方法内恰好命中一次，否则原样返回并输出 ERROR。
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

    /** 预扫描目标方法内 {@code FSTORE 16} 的命中次数。 */
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
