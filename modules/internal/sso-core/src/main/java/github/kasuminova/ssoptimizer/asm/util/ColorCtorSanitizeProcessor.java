package github.kasuminova.ssoptimizer.asm.util;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
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

import java.util.Map;

/**
 * AWT {@code Color} 构造调用点全局净化的 ASM 处理器。
 * <p>
 * 注入目标：Launch 域全部类（游戏 + 模组）中的
 * {@code new Color(int,int,int)} / {@code (int,int,int,int)} /
 * {@code (float,float,float)} / {@code (float,float,float,float)} 构造序列。<br>
 * <b>为什么不用 Mixin</b>：调用点散布在任意游戏/模组类中（实机崩溃源是模组
 * hullmod），Mixin 需要逐目标声明，无法覆盖未知调用点；{@code java.awt.Color}
 * 是 JDK 引导类，不可被改写（替换构造语义不可行）。把「NEW + DUP + 参数 +
 * INVOKESPECIAL &lt;init&gt;」序列改写为 {@code INVOKESTATIC ColorSanitizer.create}
 * 是调用点侧改写，属于跨指令序列匹配，按项目规范走 ASM。<br>
 * 实现方式：基于 ClassNode 指令树。对每个目标描述符的 {@code Color.<init>} 调用
 * 反向扫描指令流做括号匹配（途中遇到的每个 Color 构造调用消耗一个 NEW/DUP 对，
 * 从而正确处理参数中的嵌套 {@code new Color(...)} 与三元表达式等任意参数求值
 * 指令），找到配对的 {@code NEW java/awt/Color + DUP} 后将其与 init 调用一并
 * 替换为 {@code LDC site + INVOKESTATIC create}；配对失败（如子类
 * {@code super(r,g,b,a)} 链式调用，无 NEW/DUP 对）保持原样。<br>
 * 调用点标识（{@code 类名#方法名@序号}）以 LDC 烧录为工厂末参。<br>
 * 适用范围约束：仅 Launch 域类经过本处理器（System 域 JDK/lwjgl 类不经过），
 * 净化器自身类显式排除（防止自改写递归）。无 Color 常量引用的类经字节级
 * 预过滤直接跳过，不进 ASM 解析。
 */
public final class ColorCtorSanitizeProcessor implements AsmClassProcessor {

    /** 净化工厂 owner（内部名）。 */
    public static final String HELPER_OWNER = "github/kasuminova/ssoptimizer/common/util/ColorSanitizer";

    private static final String COLOR_OWNER = "java/awt/Color";
    private static final byte[] PREFILTER_PATTERN = "java/awt/Color".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** 反向配对扫描的指令数上限（超出视为病态字节码，保守放弃该调用点）。 */
    private static final int SCAN_LIMIT = 512;

    /** 构造描述符 → 工厂方法描述符（末参追加 site 字符串）。 */
    private static final Map<String, String> FACTORY_DESC = Map.of(
            "(III)V", "(IIILjava/lang/String;)Ljava/awt/Color;",
            "(IIII)V", "(IIIILjava/lang/String;)Ljava/awt/Color;",
            "(FFF)V", "(FFFLjava/lang/String;)Ljava/awt/Color;",
            "(FFFF)V", "(FFFFLjava/lang/String;)Ljava/awt/Color;");

    static {
        // 预热一次真实改写，强制本类的匿名 ClassWriter 子类在注册期完成加载。
        // 否则其首次加载发生在游戏类变换回调内：懒加载重入 transformer 链，
        // Mixin 反读游戏类字节时再次进入本处理器，撞上该子类的「定义中」状态，
        // 以 ClassCircularityError 收场（实机已验证），且此后全部调用点织入失败。
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
            // 快照迭代：改写过程中会增删指令表节点
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
                    // 无 NEW/DUP 配对（子类 super(...) 链式调用等），保持原样
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

        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * 从 {@code Color.<init>} 调用点反向扫描，括号匹配找到所属的
     * {@code NEW java/awt/Color + DUP} 指令对。
     * <p>
     * 途中的每个 Color 构造调用（任意描述符）都消耗一个 NEW/DUP 对
     * （嵌套构造在指令流中严格嵌套，与源码括号同构），故以计数器跳过；
     * 第一个未被消耗的 NEW/DUP 对即为本调用点的配对。
     *
     * @return {@code [NEW, DUP]} 指令对；未找到（非 new 表达式形态）返回 {@code null}
     */
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
            // 嵌套/改写残留形态校验：new 表达式的 DUP 必须紧跟 NEW（可隔元数据节点）
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

    /** 下一条真实指令（跳过标签/行号/帧元数据节点）。 */
    private static AbstractInsnNode nextRealInsn(final AbstractInsnNode insn) {
        AbstractInsnNode cur = insn.getNext();
        while (cur instanceof LabelNode || cur instanceof LineNumberNode || cur instanceof FrameNode) {
            cur = cur.getNext();
        }
        return cur;
    }

    /**
     * 注册期预热：用 ASM 现造一个含 {@code new Color(int,int,int)} 的微型类，
     * 完整走一遍 {@link #process} 织入路径（含 COMPUTE_FRAMES 的匿名 ClassWriter
     * 子类实例化），把全部懒加载内部类提前到静态初始化期加载。
     */
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

    /** 字节级预过滤：常量池不含 Color 引用的类直接跳过（朴素扫描足够，类加载期一次性）。 */
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
