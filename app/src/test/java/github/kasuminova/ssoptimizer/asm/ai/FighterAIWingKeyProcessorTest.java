package github.kasuminova.ssoptimizer.asm.ai;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FighterAIWingKeyProcessor} 单元测试。
 * <p>
 * 验证声明 wing 字段的假 FighterAI 被织入 {@code WingKeyProvider} 接口与
 * {@code ssoptimizer$getWingKey()} 方法；无 wing 字段或目标类不匹配时返回 null。
 */
class FighterAIWingKeyProcessorTest {

    private static final String TARGET           = "com/fs/starfarer/combat/ai/FighterAI";
    private static final String WING_FIELD_DESC  = "Lcom/fs/starfarer/combat/ai/FighterWing;";
    private static final String PROVIDER_INTERFACE = "github/kasuminova/ssoptimizer/common/combat/ai/WingKeyProvider";

    /**
     * 构造假 FighterAI：可选声明 {@code wing} 字段，构造器为空实现。
     */
    private byte[] createFakeFighterAi(String className, boolean withWingField) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        if (withWingField) {
            cw.visitField(Opcodes.ACC_PRIVATE, "wing", WING_FIELD_DESC, null, null).visitEnd();
        }

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void injectsWingKeyProviderInterfaceAndGetter() {
        byte[] rewritten = new FighterAIWingKeyProcessor().process(createFakeFighterAi(TARGET, true));
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] hasProviderInterface = {false};
        final boolean[] hasGetter = {false};
        final boolean[] getterIsPublic = {false};
        final boolean[] getterReadsWing = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                for (String itf : interfaces) {
                    if (PROVIDER_INTERFACE.equals(itf)) {
                        hasProviderInterface[0] = true;
                    }
                }
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!"ssoptimizer$getWingKey".equals(name) || !"()Ljava/lang/Object;".equals(desc)) {
                    return null;
                }
                hasGetter[0] = true;
                if ((access & Opcodes.ACC_PUBLIC) != 0) {
                    getterIsPublic[0] = true;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                        if (opcode == Opcodes.GETFIELD && TARGET.equals(owner) && "wing".equals(fieldName)) {
                            getterReadsWing[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(hasProviderInterface[0]);
        assertTrue(hasGetter[0]);
        assertTrue(getterIsPublic[0]);
        assertTrue(getterReadsWing[0]);
    }

    @Test
    void returnsNullWithoutWingField() {
        assertNull(new FighterAIWingKeyProcessor().process(createFakeFighterAi(TARGET, false)));
    }

    @Test
    void returnsNullForOtherClasses() {
        assertNull(new FighterAIWingKeyProcessor().process(
                createFakeFighterAi("com/fs/starfarer/combat/ai/OtherAI", true)));
    }
}
