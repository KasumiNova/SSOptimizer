package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AITweaks CoreLoader 字节码注入：defineClass 调用点改走合成重定向 helper，
 * 且 helper 内经 RenderThreadRedirector 改写后回调原 defineClass。
 */
class AITweaksCoreLoaderProcessorTest {
    @Test
    void rewritesDefineClassCallSiteToRedirectHelper() {
        final byte[] rewritten = new AITweaksCoreLoaderProcessor().process(targetClassBytes());
        assertNotNull(rewritten, "目标类应被注入");

        final boolean[] callSiteRewritten = {false};
        final boolean[] helperPresent = {false};
        final boolean[] helperCallsRedirect = {false};
        final boolean[] helperCallsDefineClass = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                if (AITweaksCoreLoaderProcessor.TARGET_METHOD.equals(name)
                        && AITweaksCoreLoaderProcessor.TARGET_DESC.equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(final int opcode,
                                                    final String owner,
                                                    final String methodName,
                                                    final String methodDescriptor,
                                                    final boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC
                                    && AITweaksCoreLoaderProcessor.TARGET_CLASS.equals(owner)
                                    && AITweaksCoreLoaderProcessor.HELPER_METHOD.equals(methodName)
                                    && AITweaksCoreLoaderProcessor.HELPER_DESC.equals(methodDescriptor)) {
                                callSiteRewritten[0] = true;
                            }
                            if ("defineClass".equals(methodName)) {
                                throw new AssertionError("loadClass 内不应残留 defineClass 直接调用");
                            }
                        }
                    };
                }
                if (AITweaksCoreLoaderProcessor.HELPER_METHOD.equals(name)
                        && AITweaksCoreLoaderProcessor.HELPER_DESC.equals(descriptor)) {
                    helperPresent[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(final int opcode,
                                                    final String owner,
                                                    final String methodName,
                                                    final String methodDescriptor,
                                                    final boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC
                                    && AITweaksCoreLoaderProcessor.REDIRECT_OWNER.equals(owner)
                                    && "redirect".equals(methodName)) {
                                helperCallsRedirect[0] = true;
                            }
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && AITweaksCoreLoaderProcessor.TARGET_CLASS.equals(owner)
                                    && "defineClass".equals(methodName)) {
                                helperCallsDefineClass[0] = true;
                            }
                        }
                    };
                }
                return null;
            }
        }, 0);

        assertTrue(callSiteRewritten[0], "defineClass 调用点应改写为合成重定向 helper");
        assertTrue(helperPresent[0], "应追加合成重定向 helper 方法");
        assertTrue(helperCallsRedirect[0], "helper 应先经 RenderThreadRedirector 改写字节码");
        assertTrue(helperCallsDefineClass[0], "helper 应回调原 defineClass");
    }

    private static byte[] targetClassBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                AITweaksCoreLoaderProcessor.TARGET_CLASS,
                null,
                "java/lang/ClassLoader",
                null
        );

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ClassLoader", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        // public Class<?> loadClass(String name) { return defineClass(name, new byte[0], 0, 0); }
        MethodVisitor loadClass = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                AITweaksCoreLoaderProcessor.TARGET_METHOD,
                AITweaksCoreLoaderProcessor.TARGET_DESC,
                null,
                null
        );
        loadClass.visitCode();
        loadClass.visitVarInsn(Opcodes.ALOAD, 0);
        loadClass.visitVarInsn(Opcodes.ALOAD, 1);
        loadClass.visitInsn(Opcodes.ICONST_0);
        loadClass.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        loadClass.visitInsn(Opcodes.ICONST_0);
        loadClass.visitInsn(Opcodes.ICONST_0);
        loadClass.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AITweaksCoreLoaderProcessor.TARGET_CLASS,
                "defineClass",
                AITweaksCoreLoaderProcessor.DEFINE_CLASS_DESC,
                false
        );
        loadClass.visitInsn(Opcodes.ARETURN);
        loadClass.visitMaxs(0, 0);
        loadClass.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
