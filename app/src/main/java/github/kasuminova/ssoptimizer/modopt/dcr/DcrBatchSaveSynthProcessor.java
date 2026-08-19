package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * L1（二）：向 DCR {@code data/scripts/combatanalytics/SerializationManager} 注入两个合成静态方法与一个
 * 脏标志字段，配合 {@link DcrOnGameLoadProcessor} 把 trim 重写循环的 O(N²) 降为 O(N)：
 * <ul>
 *   <li>{@code ssoptimizer$collect(CombatResult)}：{@code getAllSavedCombatResults().add(cr)} + 置脏，不序列化。</li>
 *   <li>{@code ssoptimizer$flush()}：脏时一次 {@code sort → getSerializer().toXML → saveValue}，复刻原循环末态。</li>
 *   <li>{@code ssoptimizer$dirty}：private static boolean 脏标志。</li>
 * </ul>
 * 合成方法注入 SerializationManager 自身，故可合法访问其 private {@code getSerializer()/saveValue()}；
 * {@code getAllSavedCombatResults()} 为 public。flush 用 {@code getAllSavedCombatResults()}（collect 已重建的非空
 * {@code _resultCache}），不直接读 {@code _resultCache}（{@code clearSavedData} 会将其置 null）。
 * <p>
 * {@code COMBATRESULTS_KEY} 是被内联的编译期常量，故此处直接 {@code ldc "CombatAnalytics_CombatResults_V4"}。
 * flush 仅一处分支，手写一个 {@code F_SAME} 帧；collect 线性无帧；用 {@code COMPUTE_MAXS}（非 FRAMES）避免对
 * SerializationManager 既有复杂方法重算帧。
 */
public final class DcrBatchSaveSynthProcessor implements AsmClassProcessor {

    /** 目标类 JVM 内部名。 */
    public static final String TARGET_CLASS = "data/scripts/combatanalytics/SerializationManager";

    /** DCR 持久化键（编译期常量，内联）。 */
    public static final String COMBATRESULTS_KEY = "CombatAnalytics_CombatResults_V4";

    public static final String COLLECT_NAME = "ssoptimizer$collect";
    public static final String FLUSH_NAME = "ssoptimizer$flush";
    public static final String DIRTY_FIELD = "ssoptimizer$dirty";

    private static final String COMBAT_RESULT_DESC = "(Ldata/scripts/combatanalytics/data/CombatResult;)V";
    private static final String LIST = "java/util/List";
    private static final String XSTREAM = "com/thoughtworks/xstream/XStream";
    private static final String LOGGER = "org/apache/log4j/Logger";
    private static final String HELPERS = "data/scripts/combatanalytics/util/Helpers";
    /** 与 DCR 原 saveCombatResult catch 一致的错误文案。 */
    private static final String SAVE_ERROR_MSG = "Unable to save combat result";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final ClassNode node = new ClassNode();
        reader.accept(node, 0);

        if (hasMethod(node, COLLECT_NAME)) {
            return null; // 幂等：已注入则不重复
        }

        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                DIRTY_FIELD, "Z", null, null));
        node.methods.add(buildCollect());
        node.methods.add(buildFlush());

        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean hasMethod(final ClassNode node, final String name) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    /**
     * {@code public static void ssoptimizer$collect(CombatResult cr)}：
     * {@code getAllSavedCombatResults().add(cr); ssoptimizer$dirty = true;}
     */
    private static MethodNode buildCollect() {
        final MethodNode m = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                COLLECT_NAME, COMBAT_RESULT_DESC, null, null);
        final InsnList body = new InsnList();
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET_CLASS,
                "getAllSavedCombatResults", "()Ljava/util/List;", false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, LIST, "add", "(Ljava/lang/Object;)Z", true));
        body.add(new InsnNode(Opcodes.POP));
        body.add(new InsnNode(Opcodes.ICONST_1));
        body.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET_CLASS, DIRTY_FIELD, "Z"));
        body.add(new InsnNode(Opcodes.RETURN));
        m.instructions.add(body);
        return m;
    }

    /**
     * {@code public static void ssoptimizer$flush()}：
     * <pre>{@code
     * if (!ssoptimizer$dirty) return;
     * try {
     *     List l = getAllSavedCombatResults();
     *     Collections.sort(l);
     *     saveValue(KEY, getSerializer().toXML(l));
     * } catch (Throwable e) {
     *     log.error("Unable to save combat result", e);
     *     Helpers.printErrorMessage("Unable to save combat result");
     * }
     * ssoptimizer$dirty = false;
     * }</pre>
     * try/catch(Throwable) 镜像 DCR 原 {@code saveCombatResult} 的每次保存保护：序列化/压缩失败时记录日志并
     * 正常返回（不抛出 onGameLoad），保持原版「优雅降级」语义，符合 Fail-Fast 中「核心逻辑避免崩溃」的例外。
     * <p>
     * 栈帧（不用 COMPUTE_FRAMES）：{@code doFlush} = F_SAME；异常处理器 = F_SAME1[Throwable]；
     * {@code after}（GOTO 与处理器汇合）= F_SAME。
     */
    private static MethodNode buildFlush() {
        final MethodNode m = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                FLUSH_NAME, "()V", null, null);
        final LabelNode doFlush = new LabelNode();
        final LabelNode tryStart = new LabelNode();
        final LabelNode tryEnd = new LabelNode();
        final LabelNode handler = new LabelNode();
        final LabelNode after = new LabelNode();
        final InsnList body = new InsnList();

        // if (!ssoptimizer$dirty) return;
        body.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET_CLASS, DIRTY_FIELD, "Z"));
        body.add(new JumpInsnNode(Opcodes.IFNE, doFlush));
        body.add(new InsnNode(Opcodes.RETURN));

        // doFlush: try { sort + toXML + saveValue }
        body.add(doFlush);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        body.add(tryStart);
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET_CLASS,
                "getAllSavedCombatResults", "()Ljava/util/List;", false));
        body.add(new VarInsnNode(Opcodes.ASTORE, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "sort", "(Ljava/util/List;)V", false));
        body.add(new LdcInsnNode(COMBATRESULTS_KEY));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET_CLASS, "getSerializer", "()L" + XSTREAM + ";", false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, XSTREAM, "toXML", "(Ljava/lang/Object;)Ljava/lang/String;", false));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET_CLASS, "saveValue", "(Ljava/lang/String;Ljava/lang/String;)V", false));
        body.add(tryEnd);
        body.add(new JumpInsnNode(Opcodes.GOTO, after));

        // catch (Throwable e) { log.error(MSG, e); Helpers.printErrorMessage(MSG); }
        body.add(handler);
        body.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"}));
        body.add(new VarInsnNode(Opcodes.ASTORE, 1));
        body.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET_CLASS, "log", "L" + LOGGER + ";"));
        body.add(new LdcInsnNode(SAVE_ERROR_MSG));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOGGER, "error",
                "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
        body.add(new LdcInsnNode(SAVE_ERROR_MSG));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPERS, "printErrorMessage", "(Ljava/lang/String;)V", false));

        // after: ssoptimizer$dirty = false; return;
        body.add(after);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET_CLASS, DIRTY_FIELD, "Z"));
        body.add(new InsnNode(Opcodes.RETURN));

        m.instructions.add(body);
        m.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/Throwable"));
        return m;
    }
}
