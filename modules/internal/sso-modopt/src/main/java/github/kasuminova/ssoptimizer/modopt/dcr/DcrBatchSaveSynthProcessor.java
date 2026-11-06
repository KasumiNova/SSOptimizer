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
 * 为 DCR SerializationManager 注入批量收集、统一刷写方法与脏标志。
 * <p>合成成员需以固定 JVM 名称供另一处理器直接调用，并包含手工栈帧与异常表，Mixin 不提供所需的字节码级控制。</p>
 */
public final class DcrBatchSaveSynthProcessor implements AsmClassProcessor {

    public static final String TARGET_CLASS = "data/scripts/combatanalytics/SerializationManager";

    public static final String COMBATRESULTS_KEY = "CombatAnalytics_CombatResults_V4";

    public static final String COLLECT_NAME = "ssoptimizer$collect";
    public static final String FLUSH_NAME = "ssoptimizer$flush";
    public static final String DIRTY_FIELD = "ssoptimizer$dirty";

    private static final String COMBAT_RESULT_DESC = "(Ldata/scripts/combatanalytics/data/CombatResult;)V";
    private static final String LIST = "java/util/List";
    private static final String XSTREAM = "com/thoughtworks/xstream/XStream";
    private static final String LOGGER = "org/apache/log4j/Logger";
    private static final String HELPERS = "data/scripts/combatanalytics/util/Helpers";
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
            return null;
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

    /** doFlush 与 after 使用 F_SAME，handler 使用 F_SAME1[Throwable]，避免重算目标类既有栈帧。 */
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

        body.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET_CLASS, DIRTY_FIELD, "Z"));
        body.add(new JumpInsnNode(Opcodes.IFNE, doFlush));
        body.add(new InsnNode(Opcodes.RETURN));

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
