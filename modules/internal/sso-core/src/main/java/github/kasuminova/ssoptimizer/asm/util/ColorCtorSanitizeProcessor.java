package github.kasuminova.ssoptimizer.asm.util;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 Launch 域类中的 AWT {@code Color} 构造调用改为 {@link ColorSanitizer} 工厂调用。
 * 调用点分布不可枚举且 JDK 构造器不能改写，Mixin 无法覆盖，故使用 ASM 匹配构造指令序列。
 * 改写保持操作数栈形态；参数窗口内的帧必须同步移除未初始化项，形态异常时保留原字节码并告警。
 */
public final class ColorCtorSanitizeProcessor implements AsmClassProcessor {

    private static final Logger LOGGER = Logger.getLogger(ColorCtorSanitizeProcessor.class);

    public static final String HELPER_OWNER = "github/kasuminova/ssoptimizer/common/util/ColorSanitizer";

    private static final String COLOR_OWNER = "java/awt/Color";
    private static final byte[] PREFILTER_PATTERN = "java/awt/Color".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static final int SCAN_LIMIT = 512;

    private static final Map<String, String> FACTORY_DESC = Map.of(
            "(III)V", "(IIILjava/lang/String;)Ljava/awt/Color;",
            "(IIII)V", "(IIIILjava/lang/String;)Ljava/awt/Color;",
            "(FFF)V", "(FFFLjava/lang/String;)Ljava/awt/Color;",
            "(FFFF)V", "(FFFFLjava/lang/String;)Ljava/awt/Color;");

    static {
        // 提前加载改写路径的 ASM 类，避免在游戏类变换回调内懒加载并造成类定义循环。
        warmUp();
    }

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        if (!containsBytes(classfileBuffer, PREFILTER_PATTERN)) {
            return null;
        }
        final ClassReader reader = new ClassReader(classfileBuffer);
        final String className = reader.getClassName();
        if (HELPER_OWNER.equals(className)) {
            return null;
        }

        final ClassNode node = new ClassNode();
        reader.accept(node, 0);

        boolean changed = false;
        for (MethodNode method : node.methods) {
            int siteIndex = 0;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.INVOKESPECIAL || !(insn instanceof MethodInsnNode)) {
                    continue;
                }
                final MethodInsnNode init = (MethodInsnNode) insn;
                if (!COLOR_OWNER.equals(init.owner) || !"<init>".equals(init.name)) {
                    continue;
                }
                final String factoryDesc = FACTORY_DESC.get(init.desc);
                if (factoryDesc == null) {
                    continue;
                }
                final AbstractInsnNode[] pair = findNewDupPair(init);
                if (pair == null) {
                    continue;
                }
                if (!fixWindowFrames(pair[0], init)) {
                    LOGGER.warn("[SSOptimizer] Color 构造调用点帧形态不符预期，保持原版字节码: "
                            + className + "#" + method.name + "@" + siteIndex);
                    continue;
                }
                final InsnList replacement = new InsnList();
                replacement.add(new LdcInsnNode(className + "#" + method.name + "@" + siteIndex++));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER_OWNER, "create", factoryDesc, false));
                method.instructions.insertBefore(init, replacement);
                method.instructions.remove(pair[0]);
                method.instructions.remove(pair[1]);
                method.instructions.remove(init);
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }

        // 栈形态不变，保留原始帧与 maxs，仅修补参数窗口内的未初始化项。
        final ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** 移除参数窗口帧中指向当前 NEW 标签的两条未初始化项，形态异常时不做部分修改。 */
    private static boolean fixWindowFrames(final AbstractInsnNode newInsn, final AbstractInsnNode init) {
        LabelNode newLabel = null;
        final List<FrameNode> windowFrames = new ArrayList<>();
        for (AbstractInsnNode cur = newInsn.getNext(); cur != null && cur != init; cur = cur.getNext()) {
            if (cur instanceof FrameNode) {
                windowFrames.add((FrameNode) cur);
            }
        }
        if (windowFrames.isEmpty()) {
            return true;
        }
        // 帧以标签表示未初始化项，存在窗口帧则 NEW 处必有标签（帧解析时会创建）
        for (AbstractInsnNode cur = newInsn.getPrevious(); cur != null; cur = cur.getPrevious()) {
            if (cur instanceof LabelNode) {
                newLabel = (LabelNode) cur;
                break;
            }
            if (!(cur instanceof LineNumberNode) && !(cur instanceof FrameNode)) {
                break;
            }
        }
        if (newLabel == null) {
            return false;
        }
        final LabelNode label = newLabel;
        // 先全量校验再统一剔除：中途失败不得留下改了一半的帧
        for (FrameNode frame : windowFrames) {
            if (frame.type != Opcodes.F_FULL || frame.stack == null || countRefs(frame, label) != 2) {
                return false;
            }
        }
        for (FrameNode frame : windowFrames) {
            frame.stack.removeIf(entry -> entry == label);
        }
        return true;
    }

    private static int countRefs(final FrameNode frame, final LabelNode newLabel) {
        int count = 0;
        for (Object entry : frame.stack) {
            if (entry == newLabel) {
                count++;
            }
        }
        return count;
    }

    /** 反向配对嵌套构造调用，未找到 NEW/DUP 对时返回 null。 */
    private static AbstractInsnNode[] findNewDupPair(final MethodInsnNode init) {
        int unmatchedInits = 0;
        int scanned = 0;
        for (AbstractInsnNode cur = init.getPrevious(); cur != null && scanned < SCAN_LIMIT;
             cur = cur.getPrevious(), scanned++) {
            if (cur.getOpcode() == Opcodes.INVOKESPECIAL && cur instanceof MethodInsnNode) {
                final MethodInsnNode m = (MethodInsnNode) cur;
                if (COLOR_OWNER.equals(m.owner) && "<init>".equals(m.name)) {
                    unmatchedInits++;
                }
                continue;
            }
            if (cur.getOpcode() != Opcodes.NEW || !(cur instanceof TypeInsnNode)
                    || !COLOR_OWNER.equals(((TypeInsnNode) cur).desc)) {
                continue;
            }
            final AbstractInsnNode next = nextRealInsn(cur);
            if (next == null || next.getOpcode() != Opcodes.DUP) {
                continue;
            }
            if (unmatchedInits > 0) {
                unmatchedInits--;
                continue;
            }
            return new AbstractInsnNode[]{cur, next};
        }
        return null;
    }

    private static AbstractInsnNode nextRealInsn(final AbstractInsnNode insn) {
        AbstractInsnNode cur = insn.getNext();
        while (cur instanceof LabelNode || cur instanceof LineNumberNode || cur instanceof FrameNode) {
            cur = cur.getNext();
        }
        return cur;
    }

    private static void warmUp() {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_FINAL,
                "github/kasuminova/ssoptimizer/asm/util/ColorCtorSanitizeWarmup", null, "java/lang/Object", null);
        final org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC,
                "make", "()Ljava/awt/Color;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, COLOR_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COLOR_OWNER, "<init>", "(III)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        new ColorCtorSanitizeProcessor().process(cw.toByteArray());
    }

    private static boolean containsBytes(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
