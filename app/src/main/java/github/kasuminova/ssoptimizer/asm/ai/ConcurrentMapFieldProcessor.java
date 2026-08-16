package github.kasuminova.ssoptimizer.asm.ai;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * 字段初始化的普通 Map 并发化 ASM 处理器。
 * <p>
 * 注入动机：AI 并行化后，{@code ShipwideAIFlags.flags}（HashMap）与
 * {@code TimeoutTrackerMap.items}（LinkedHashMap）可能被多个 AI 工作线程并发读写
 * （后者迭代语义变为弱一致，原 LinkedHashMap 顺序在超时清理场景无业务依赖）。<br>
 * 注入效果：字段初始化处的 {@code NEW <原类型>; DUP; INVOKESPECIAL <原类型>.<init>;
 * PUTFIELD <字段>} 序列替换为 {@code ConcurrentHashMap}。字段类型声明为
 * {@code java.util.Map}，无需改动。
 */
public final class ConcurrentMapFieldProcessor implements AsmClassProcessor {
    private static final Logger LOGGER = Logger.getLogger(ConcurrentMapFieldProcessor.class);

    private static final String CONCURRENT_MAP = "java/util/concurrent/ConcurrentHashMap";

    private final String targetClass;
    private final String fieldName;
    private final String originalType;

    /**
     * @param targetClass  目标类（内部名，如 {@code com/fs/starfarer/api/combat/ShipwideAIFlags}）
     * @param fieldName    目标字段名（如 {@code flags}）
     * @param originalType 原 Map 实现内部名（如 {@code java/util/HashMap}）
     */
    public ConcurrentMapFieldProcessor(String targetClass, String fieldName, String originalType) {
        this.targetClass = targetClass;
        this.fieldName = fieldName;
        this.originalType = originalType;
    }

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!targetClass.equals(reader.getClassName())) {
            return null;
        }

        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);

        boolean modified = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode put) || insn.getOpcode() != Opcodes.PUTFIELD
                        || !fieldName.equals(put.name) || !targetClass.equals(put.owner)) {
                    continue;
                }
                AbstractInsnNode init = put.getPrevious();
                AbstractInsnNode dup = init != null ? init.getPrevious() : null;
                AbstractInsnNode alloc = dup != null ? dup.getPrevious() : null;
                if (alloc instanceof TypeInsnNode typeInsn && alloc.getOpcode() == Opcodes.NEW
                        && originalType.equals(typeInsn.desc)
                        && dup.getOpcode() == Opcodes.DUP
                        && init instanceof MethodInsnNode initCall
                        && originalType.equals(initCall.owner) && "<init>".equals(initCall.name)) {
                    typeInsn.desc = CONCURRENT_MAP;
                    initCall.owner = CONCURRENT_MAP;
                    modified = true;
                }
            }
        }

        if (!modified) {
            LOGGER.warn("[SSOptimizer] ConcurrentMapFieldProcessor found no " + originalType
                    + " allocation for " + targetClass + "." + fieldName);
            return null;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
}
