package github.kasuminova.ssoptimizer.asm.automation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ASTD 自动化插件字节码注入会在 telemetry 入口调用 SSOptimizer 独立截图 helper。
 */
class ASTDAutomationCombatPluginProcessorTest {
    @Test
    void injectsIndependentScreenshotHelperAtTelemetryEntry() {
        final byte[] rewritten = new ASTDAutomationCombatPluginProcessor().process(targetClassBytes());
        assertNotNull(rewritten, "目标类应被注入");

        final boolean[] foundHelperCall = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                if (!ASTDAutomationCombatPluginProcessor.TARGET_METHOD.equals(name)
                        || !ASTDAutomationCombatPluginProcessor.TARGET_DESC.equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode,
                                                final String owner,
                                                final String methodName,
                                                final String methodDescriptor,
                                                final boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && ASTDAutomationCombatPluginProcessor.HELPER_OWNER.equals(owner)
                                && ASTDAutomationCombatPluginProcessor.HELPER_METHOD.equals(methodName)
                                && ASTDAutomationCombatPluginProcessor.HELPER_DESC.equals(methodDescriptor)) {
                            foundHelperCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundHelperCall[0], "telemetry 方法入口应调用独立截图 helper");
    }

    private static byte[] targetClassBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                ASTDAutomationCombatPluginProcessor.TARGET_CLASS,
                null,
                "java/lang/Object",
                null
        );

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor telemetry = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                ASTDAutomationCombatPluginProcessor.TARGET_METHOD,
                ASTDAutomationCombatPluginProcessor.TARGET_DESC,
                null,
                null
        );
        telemetry.visitCode();
        telemetry.visitInsn(Opcodes.RETURN);
        telemetry.visitMaxs(0, 0);
        telemetry.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}