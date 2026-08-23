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
 * 伤害结算第二阶段 NaN/Inf 守卫的 ASM 处理器。
 * <p>
 * 注入目标：{@code Ship.applyDamageInner(Vector2f, Damage, boolean, boolean, float, Object)}
 * （六参重载，五参委托至此）。<br>
 * <b>为什么不用 Mixin</b>：检查值是方法中部经整条 damage-taken 修正链迭代写入的计算局部量
 * （槽 13/15/17），而混淆游戏 jar 的 LocalVariableTable 只保留方法参数（已用 javap 坐实），
 * Mixin locals capture 无表可依；项目亦无 MixinExtras（无 {@code @Local} 槽位捕获）。
 * 在 INVOKE 锚点读中段局部槽只能由 ASM 完成，属于项目规范允许的例外情形。<br>
 * 注入动机：第一阶段 HEAD 守卫只覆盖「调用方传入即为坏值」的场景；实测 35 条 NaN 辐能日志
 * 全部是入口有限、经目标侧修正链（dmg_vs_*、护盾/装甲 damage-taken、easyMult、
 * {@code shield.getFluxPerPointOfDamage} 等）后才变坏的值，HEAD 看不见。<br>
 * 注入效果（两个锚点覆盖「所有 modifier 应用后、首次 armor/hull/flux 副作用前」的全部写路径）：
 * <ul>
 *   <li>锚点 A（护盾/辐能路径）：护盾辐能当量最终写入处——指令序列
 *       {@code INVOKEVIRTUAL Shield.getFluxPerPointOfDamage()F; FMUL; FSTORE 13}
 *       （原版 bytecode 1237-1241，方法内唯一）之后。此时操作数栈为空，检查槽 13/15；
 *       锚 {@code Shield.shieldHit}（bytecode 1256）的替代位更靠前——shieldHit 本身就是
 *       本分支首个副作用，跳过它才不留半事件，故不选 increaseFlux（bytecode 1383）锚。</li>
 *   <li>锚点 B（装甲/结构路径）：原版放行守卫 {@code if (slot13<=0 && slot15<=0) return}
 *       的完整八指令序列 {@code FLOAD 13; FCONST_0; FCMPL; IFGT; FLOAD 15; FCONST_0; FCMPL; IFLE}
 *       （bytecode 1524-1535，方法内唯一）的 fall-through 处，操作数栈为空，检查槽 13/17/15。
 *       该位置同时覆盖 bypassShields 直伤分支（共用此 fall-through），优于锚
 *       {@code ArmorGrid.applyDamage} INVOKE（bytecode 1584，bypass 分支会绕过它）。</li>
 * </ul>
 * 命中即整单取消：先 {@code Profiler.end()} 配对方法头的 {@code Profiler.begin("Ship.applyDamage")}
 * （原版所有出口含 catch-any 兜底均如此），再返回空 {@code ApplyDamageResult}
 * （与原版 damage<=0 早退语义一致，公共无参构造已确认存在）。<br>
 * 锚点失配防护：预扫描要求两模式在目标方法内各恰好命中一次，否则原样返回并输出 ERROR
 * （游戏版本漂移时守卫宁可整体缺席也不半套织入）。
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

        // 预扫描：两锚点必须在目标方法内各恰好命中一次，漂移即放弃织入并留 ERROR
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

    /** 预扫描两个锚点模式在目标方法内的命中次数。 */
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

    /** 目标方法的织入适配器。 */
    static final class StageTwoMethodAdapter extends AnchorWindow {
        StageTwoMethodAdapter(final MethodVisitor delegate) {
            super(delegate);
        }

        @Override
        void onShieldAnchor() {
            // 栈空。检查槽 13（护盾辐能当量）与槽 15（EMP）
            emitGuard(SHIELD_GUARD_METHOD, SHIELD_GUARD_DESC, new int[]{13, 15});
        }

        @Override
        void onArmorAnchor() {
            // 栈空。检查槽 13（最终伤害）、槽 17（装甲伤害量）、槽 15（EMP）
            emitGuard(ARMOR_GUARD_METHOD, ARMOR_GUARD_DESC, new int[]{13, 17, 15});
        }

        /**
         * 发射「守卫判定 → 命中则 Profiler.end() + 返回空 ApplyDamageResult」序列。
         *
         * @param floatSlots 依次 FLOAD 的局部槽（与 guard 方法前若干 float 参数一一对应）
         */
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
            // 整单取消：配对方法头 Profiler.begin，再按原版 damage<=0 早退语义返回空结果
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER_OWNER, "end", "()V", false);
            mv.visitTypeInsn(Opcodes.NEW, RESULT_CLASS);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RESULT_CLASS, "<init>", "()V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(proceed);
        }
    }

    /**
     * 锚点模式识别基类：维护最近 8 条真实指令的滚动窗口（行号/帧/标签不参与），
     * 逐条透传并在命中模式后回调。
     */
    static abstract class AnchorWindow extends MethodVisitor {
        /** 指令签名滚动窗口（最多 8 条）。 */
        private final String[] window = new String[8];
        private int size;

        AnchorWindow(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        /** 锚点 A 命中回调（当前指令为序列末位的 {@code FSTORE 13}，已透传）。 */
        abstract void onShieldAnchor();

        /** 锚点 B 命中回调（当前指令为序列末位的 {@code IFLE}，已透传）。 */
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

        /** 最近 {@code count} 条指令是否与给定序列完全相等（窗口末位对齐）。 */
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
