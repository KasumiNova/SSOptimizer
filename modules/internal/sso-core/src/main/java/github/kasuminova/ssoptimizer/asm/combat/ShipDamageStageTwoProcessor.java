package github.kasuminova.ssoptimizer.asm.combat;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 在伤害修正完成后、首次结算副作用前检查方法中部的伤害与辐能局部量。
 * 混淆类缺少局部变量表，Mixin 无法捕获这些槽位，故使用 ASM 匹配两组指令锚点。
 * 两组锚点均须唯一；命中坏值时结束 Profiler 配对并返回空结果，锚点漂移时保留原字节码并记录错误。
 */
public final class ShipDamageStageTwoProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = GameClassNames.SHIP;
    public static final String TARGET_METHOD = "applyDamageInner";
    public static final String TARGET_DESC = "(Lorg/lwjgl/util/vector/Vector2f;Lcom/fs/starfarer/combat/Damage;ZZF"
            + "Ljava/lang/Object;)Lcom/fs/starfarer/combat/entities/ship/ApplyDamageResult;";

    private static final Logger LOGGER = Logger.getLogger(ShipDamageStageTwoProcessor.class);

    private static final String HELPER_OWNER = "github/kasuminova/ssoptimizer/common/combat/CombatNaNGuard";
    private static final String SHIELD_GUARD_METHOD = "shouldDiscardShieldDamage";
    private static final String SHIELD_GUARD_DESC =
            "(FFLcom/fs/starfarer/combat/Damage;FLjava/lang/Object;Ljava/lang/Object;)Z";
    private static final String ARMOR_GUARD_METHOD = "shouldDiscardArmorDamage";
    private static final String ARMOR_GUARD_DESC =
            "(FFFLcom/fs/starfarer/combat/Damage;FLjava/lang/Object;Ljava/lang/Object;)Z";
    private static final String PROFILER_OWNER = "com/fs/profiler/Profiler";
    private static final String RESULT_CLASS = "com/fs/starfarer/combat/entities/ship/ApplyDamageResult";
    private static final String SHIELD_OWNER = GameClassNames.SHIELD;
    private static final String FLUX_PER_POINT = "getFluxPerPointOfDamage";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final int[] anchors = countAnchors(reader);
        if (anchors[0] != 1 || anchors[1] != 1) {
            LOGGER.error("[SSOptimizer] 第二阶段伤害守卫锚点失配: shieldFlux=" + anchors[0]
                    + " armor=" + anchors[1] + "（应各为 1），applyDamageInner 保持原版字节码");
            return null;
        }

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return AsmCommonSuperClassResolver.resolve(type1, type2);
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
                return new StageTwoMethodAdapter(delegate);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static int[] countAnchors(final ClassReader reader) {
        final int[] counts = new int[2];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String sig, final String[] ex) {
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new AnchorWindow(null) {
                    @Override
                    void onShieldAnchor() {
                        counts[0]++;
                    }

                    @Override
                    void onArmorAnchor() {
                        counts[1]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }

    static final class StageTwoMethodAdapter extends AnchorWindow {
        StageTwoMethodAdapter(final MethodVisitor delegate) {
            super(delegate);
        }

        @Override
        void onShieldAnchor() {
            emitGuard(SHIELD_GUARD_METHOD, SHIELD_GUARD_DESC, new int[]{13, 15});
        }

        @Override
        void onArmorAnchor() {
            emitGuard(ARMOR_GUARD_METHOD, ARMOR_GUARD_DESC, new int[]{13, 17, 15});
        }

        /** 生成守卫判断；命中时先配对结束 Profiler，再返回空结算结果。 */
        private void emitGuard(final String method, final String desc, final int[] floatSlots) {
            for (final int slot : floatSlots) {
                mv.visitVarInsn(Opcodes.FLOAD, slot);
            }
            mv.visitVarInsn(Opcodes.ALOAD, 2);   // damage
            mv.visitVarInsn(Opcodes.FLOAD, 5);   // damageMult
            mv.visitVarInsn(Opcodes.ALOAD, 6);   // source
            mv.visitVarInsn(Opcodes.ALOAD, 0);   // this ship
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, method, desc, false);
            final Label proceed = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, proceed);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER_OWNER, "end", "()V", false);
            mv.visitTypeInsn(Opcodes.NEW, RESULT_CLASS);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RESULT_CLASS, "<init>", "()V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(proceed);
        }
    }

    /** 以最近八条真实指令的滚动窗口匹配锚点。 */
    static abstract class AnchorWindow extends MethodVisitor {
        private final String[] window = new String[8];
        private int size;

        AnchorWindow(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        abstract void onShieldAnchor();

        abstract void onArmorAnchor();

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name,
                                    final String descriptor, final boolean isInterface) {
            if (mv != null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
            push("M" + opcode + ":" + owner + "." + name + descriptor);
        }

        @Override
        public void visitVarInsn(final int opcode, final int variable) {
            if (mv != null) {
                super.visitVarInsn(opcode, variable);
            }
            push("V" + opcode + ":" + variable);
            if (opcode == Opcodes.FSTORE && variable == 13 && matches(3,
                    "M" + Opcodes.INVOKEVIRTUAL + ":" + SHIELD_OWNER + "." + FLUX_PER_POINT + "()F",
                    "I" + Opcodes.FMUL,
                    "V" + Opcodes.FSTORE + ":13")) {
                onShieldAnchor();
            }
        }

        @Override
        public void visitInsn(final int opcode) {
            if (mv != null) {
                super.visitInsn(opcode);
            }
            push("I" + opcode);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            if (mv != null) {
                super.visitJumpInsn(opcode, label);
            }
            push("J" + opcode);
            if (opcode == Opcodes.IFLE && matches(8,
                    "V" + Opcodes.FLOAD + ":13",
                    "I" + Opcodes.FCONST_0,
                    "I" + Opcodes.FCMPL,
                    "J" + Opcodes.IFGT,
                    "V" + Opcodes.FLOAD + ":15",
                    "I" + Opcodes.FCONST_0,
                    "I" + Opcodes.FCMPL,
                    "J" + Opcodes.IFLE)) {
                onArmorAnchor();
            }
        }

        private void push(final String insn) {
            if (size < window.length) {
                window[size++] = insn;
                return;
            }
            System.arraycopy(window, 1, window, 0, window.length - 1);
            window[window.length - 1] = insn;
        }

        private boolean matches(final int count, final String... expected) {
            if (size < count) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (!window[size - count + i].equals(expected[i])) {
                    return false;
                }
            }
            return true;
        }
    }
}
