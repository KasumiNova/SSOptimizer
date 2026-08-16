package github.kasuminova.ssoptimizer.asm.ai;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConcurrentMapFieldProcessor} 单元测试。
 * <p>
 * 验证字段初始化处的 {@code NEW HashMap; DUP; INVOKESPECIAL <init>; PUTFIELD flags}
 * 序列被替换为 ConcurrentHashMap；originalType 不匹配或目标类不匹配时返回 null。
 */
class ConcurrentMapFieldProcessorTest {

    private static final String TARGET         = "com/fs/starfarer/api/combat/ShipwideAIFlags";
    private static final String HASH_MAP       = "java/util/HashMap";
    private static final String CONCURRENT_MAP = "java/util/concurrent/ConcurrentHashMap";

    /**
     * 构造假 ShipwideAIFlags：声明 {@code flags Ljava/util/Map;} 字段，
     * 构造器内以 HashMap 初始化该字段。
     */
    private byte[] createFakeShipwideAiFlags(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "flags", "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, HASH_MAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, "flags", "Ljava/util/Map;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void replacesHashMapAllocationWithConcurrentHashMap() {
        byte[] rewritten = new ConcurrentMapFieldProcessor(TARGET, "flags", HASH_MAP)
                .process(createFakeShipwideAiFlags(TARGET));
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

        assertEquals(0, hashMapNews[0]);
        assertEquals(1, concurrentMapNews[0]);
        assertEquals(1, concurrentMapInits[0]);
    }

    @Test
    void returnsNullWhenOriginalTypeMismatches() {
        // 假类实际用 HashMap 初始化，originalType 传 LinkedHashMap 应匹配失败
        assertNull(new ConcurrentMapFieldProcessor(TARGET, "flags", "java/util/LinkedHashMap")
                .process(createFakeShipwideAiFlags(TARGET)));
    }

    @Test
    void returnsNullForOtherClasses() {
        assertNull(new ConcurrentMapFieldProcessor(TARGET, "flags", HASH_MAP)
                .process(createFakeShipwideAiFlags("com/fs/starfarer/api/combat/OtherFlags")));
    }
}
