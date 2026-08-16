package github.kasuminova.ssoptimizer.asm.ai;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * 战斗引擎 AI 循环并行化与 customData 并发化的 ASM 处理器。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatEngine}<br>
 * 注入动机：{@code advanceInner} 内实体 AI 串行执行是大规模战场 advance 阶段的主要开销；
 * Mixin 无法精确改写方法体内单个调用指令。<br>
 * 注入效果：
 * <ol>
 *   <li>AI 循环内第一个 {@code AI.advance(F)V} 调用（字节码含 fast-time 段第二处调用，不动）
 *       替换为 {@code ParallelAiDispatcher.dispatch(AI, F)V}，栈形状完全一致；</li>
 *   <li>循环结束后的 {@code ldc "Advancing entities"} 之前插入
 *       {@code ParallelAiDispatcher.awaitAll()V} 帧内屏障；</li>
 *   <li>{@code customData} 字段两处 {@code new HashMap<>()}（构造与 reset）
 *       替换为 {@code ConcurrentHashMap}——AI 并行后模组/AI 代码可能在工作线程读写。</li>
 * </ol>
 */
public final class CombatEngineAiLoopProcessor implements AsmClassProcessor {
    private static final Logger LOGGER = Logger.getLogger(CombatEngineAiLoopProcessor.class);

    private static final String TARGET_CLASS   = GameClassNames.COMBAT_ENGINE;
    private static final String AI_OWNER       = GameClassNames.AI_INTERFACE;
    private static final String DISPATCH_OWNER = "github/kasuminova/ssoptimizer/common/combat/ai/ParallelAiDispatcher";
    private static final String DISPATCH_DESC  = "(Lcom/fs/starfarer/combat/ai/AI;F)V";
    private static final String HASH_MAP       = "java/util/HashMap";
    private static final String CONCURRENT_MAP = "java/util/concurrent/ConcurrentHashMap";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);

        boolean modified = transformAdvanceInner(node);
        modified |= makeCustomDataConcurrent(node);

        if (!modified) {
            LOGGER.warn("[SSOptimizer] CombatEngineAiLoopProcessor matched no pattern; class left unchanged");
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

    /**
     * AI 循环：替换第一个 AI.advance 调用点并在循环后插入 awaitAll 屏障。
     */
    private boolean transformAdvanceInner(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!"advanceInner".equals(method.name) || !"(FLcom/fs/starfarer/util/InputEventList;)V".equals(method.desc)) {
                continue;
            }
            boolean dispatchDone = false;
            boolean barrierDone = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!dispatchDone
                        && insn.getOpcode() == Opcodes.INVOKEINTERFACE
                        && insn instanceof MethodInsnNode call
                        && AI_OWNER.equals(call.owner) && "advance".equals(call.name) && "(F)V".equals(call.desc)) {
                    method.instructions.set(insn, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, DISPATCH_OWNER, "dispatch", DISPATCH_DESC, false));
                    dispatchDone = true;
                    continue;
                }
                if (dispatchDone && !barrierDone
                        && insn instanceof LdcInsnNode ldc
                        && "Advancing entities".equals(ldc.cst)) {
                    method.instructions.insertBefore(insn, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, DISPATCH_OWNER, "awaitAll", "()V", false));
                    barrierDone = true;
                }
            }
            if (dispatchDone && barrierDone) {
                return true;
            }
            LOGGER.warn("[SSOptimizer] advanceInner pattern incomplete: dispatch=" + dispatchDone + ", barrier=" + barrierDone);
            return false;
        }
        LOGGER.warn("[SSOptimizer] advanceInner method not found in CombatEngine");
        return false;
    }

    /**
     * customData 字段的两处 HashMap 实例化替换为 ConcurrentHashMap。
     * 模式：{@code NEW HashMap; DUP; INVOKESPECIAL HashMap.<init>; PUTFIELD customData}。
     */
    private boolean makeCustomDataConcurrent(ClassNode node) {
        boolean modified = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode put) || insn.getOpcode() != Opcodes.PUTFIELD
                        || !"customData".equals(put.name) || !TARGET_CLASS.equals(put.owner)) {
                    continue;
                }
                AbstractInsnNode init = put.getPrevious();
                AbstractInsnNode dup = init != null ? init.getPrevious() : null;
                AbstractInsnNode alloc = dup != null ? dup.getPrevious() : null;
                if (alloc instanceof TypeInsnNode typeInsn && alloc.getOpcode() == Opcodes.NEW
                        && HASH_MAP.equals(typeInsn.desc)
                        && dup.getOpcode() == Opcodes.DUP
                        && init instanceof MethodInsnNode initCall
                        && HASH_MAP.equals(initCall.owner) && "<init>".equals(initCall.name)) {
                    typeInsn.desc = CONCURRENT_MAP;
                    initCall.owner = CONCURRENT_MAP;
                    modified = true;
                } else {
                    LOGGER.warn("[SSOptimizer] customData PUTFIELD without HashMap allocation pattern in "
                            + method.name + "; skipped");
                }
            }
        }
        if (!modified) {
            LOGGER.warn("[SSOptimizer] customData HashMap allocations not found");
        }
        return modified;
    }
}
