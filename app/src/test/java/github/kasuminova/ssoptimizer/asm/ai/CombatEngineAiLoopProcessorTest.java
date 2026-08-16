package github.kasuminova.ssoptimizer.asm.ai;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CombatEngineAiLoopProcessor} 单元测试。
 * <p>
 * 用 ASM ClassWriter 构造假 CombatEngine 字节码，验证三件事：
 * <ol>
 *   <li>advanceInner 内第一个 {@code AI.advance(F)V} 调用被替换为
 *       {@code ParallelAiDispatcher.dispatch(AI, F)V}，第二个调用保持 INVOKEINTERFACE；</li>
 *   <li>{@code ldc "Advancing entities"} 之前插入 {@code awaitAll()V} 帧内屏障；</li>
 *   <li>{@code customData} 两处 {@code new HashMap<>()} 替换为 ConcurrentHashMap。</li>
 * </ol>
 */
class CombatEngineAiLoopProcessorTest {

    private static final String TARGET          = "com/fs/starfarer/combat/CombatEngine";
    private static final String AI_OWNER        = "com/fs/starfarer/combat/ai/AI";
    private static final String DISPATCH_OWNER  = "github/kasuminova/ssoptimizer/common/combat/ai/ParallelAiDispatcher";
    private static final String HASH_MAP        = "java/util/HashMap";
    private static final String CONCURRENT_MAP  = "java/util/concurrent/ConcurrentHashMap";

    /**
     * 构造含以下内容的假 CombatEngine：
     * <ul>
     *   <li>{@code advanceInner(F InputEventList)V}：两处 AI.advance INVOKEINTERFACE
     *       （模拟主循环段与 fast-time 段）+ {@code ldc "Advancing entities"}；</li>
     *   <li>{@code <init>} 与 {@code reset}：各含一段
     *       {@code NEW HashMap; DUP; INVOKESPECIAL HashMap.<init>; PUTFIELD customData}。</li>
     * </ul>
     */
    private byte[] createFakeCombatEngine(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE, "ai", "L" + AI_OWNER + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "customData", "Ljava/util/Map;", null, null).visitEnd();

        // advanceInner：两次 AI.advance（此处 AI 引用取自 this.ai 字段）
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "advanceInner",
                "(FLcom/fs/starfarer/util/InputEventList;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, "ai", "L" + AI_OWNER + ";");
        mv.visitVarInsn(Opcodes.FLOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, AI_OWNER, "advance", "(F)V", true);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, "ai", "L" + AI_OWNER + ";");
        mv.visitVarInsn(Opcodes.FLOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, AI_OWNER, "advance", "(F)V", true);
        mv.visitLdcInsn("Advancing entities");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // customData 初始化：构造与 reset 各一处 HashMap 分配
        addCustomDataInit(cw, "<init>");
        addCustomDataInit(cw, "reset");

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 写入 {@code this.customData = new HashMap<>();} 方法体。 */
    private void addCustomDataInit(ClassWriter cw, String methodName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, HASH_MAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, TARGET, "customData", "Ljava/util/Map;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @Test
    void rewritesFirstAiAdvanceAndInsertsAwaitAll() {
        byte[] rewritten = new CombatEngineAiLoopProcessor().process(createFakeCombatEngine(TARGET));
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final int[] dispatchCalls = {0};       // ParallelAiDispatcher.dispatch 调用次数
        final int[] directAiAdvance = {0};     // 未被替换的 AI.advance INVOKEINTERFACE 次数
        final boolean[] awaitAllBeforeMessage = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!"advanceInner".equals(name)) {
                    return null;
                }
                // 记录"已见到 awaitAll"，随后遇到 "Advancing entities" 时确认其已先行出现
                final boolean[] sawAwaitAll = {false};
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (DISPATCH_OWNER.equals(owner) && "dispatch".equals(methodName)
                                && ("(L" + AI_OWNER + ";F)V").equals(methodDesc)) {
                            dispatchCalls[0]++;
                        }
                        if (AI_OWNER.equals(owner) && "advance".equals(methodName)) {
                            directAiAdvance[0]++;
                        }
                        if (opcode == Opcodes.INVOKESTATIC && DISPATCH_OWNER.equals(owner)
                                && "awaitAll".equals(methodName) && "()V".equals(methodDesc)) {
                            sawAwaitAll[0] = true;
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if ("Advancing entities".equals(value) && sawAwaitAll[0]) {
                            awaitAllBeforeMessage[0] = true;
                        }
                    }
                };
            }
        }, 0);

        // 第一个调用替换为 dispatch，第二个保持 INVOKEINTERFACE
        assertEquals(1, dispatchCalls[0]);
        assertEquals(1, directAiAdvance[0]);
        // awaitAll 屏障位于 "Advancing entities" 之前
        assertTrue(awaitAllBeforeMessage[0]);
    }

    @Test
    void makesCustomDataConcurrent() {
        byte[] rewritten = new CombatEngineAiLoopProcessor().process(createFakeCombatEngine(TARGET));
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final int[] hashMapNews = {0};
        final int[] concurrentMapNews = {0};
        final int[] concurrentMapInits = {0};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode != Opcodes.NEW) {
                            return;
                        }
                        if (HASH_MAP.equals(type)) {
                            hashMapNews[0]++;
                        } else if (CONCURRENT_MAP.equals(type)) {
                            concurrentMapNews[0]++;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (opcode == Opcodes.INVOKESPECIAL && CONCURRENT_MAP.equals(owner) && "<init>".equals(methodName)) {
                            concurrentMapInits[0]++;
                        }
                    }
                };
            }
        }, 0);

        // 两处 HashMap 分配（<init> 与 reset）均被替换为 ConcurrentHashMap
        assertEquals(0, hashMapNews[0]);
        assertEquals(2, concurrentMapNews[0]);
        assertEquals(2, concurrentMapInits[0]);
    }

    @Test
    void returnsNullForOtherClasses() {
        assertNull(new CombatEngineAiLoopProcessor().process(
                createFakeCombatEngine("com/fs/starfarer/combat/OtherEngine")));
    }
}
