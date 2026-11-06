package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 将 CoreLoader 的 defineClass 调用改经渲染重定向。
 * <p>目标类在 Mixin 解析目标时尚不可加载，且需新增同类合成方法访问 protected final defineClass，因此使用 ASM。</p>
 */
public final class AITweaksCoreLoaderProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "com/genir/aitweaks/launcher/loading/CoreLoader";
    public static final String TARGET_METHOD = "loadClass";
    public static final String TARGET_DESC = "(Ljava/lang/String;)Ljava/lang/Class;";
    public static final String DEFINE_CLASS_DESC = "(Ljava/lang/String;[BII)Ljava/lang/Class;";
    public static final String HELPER_METHOD = "ssoptimizer$defineClassRedirected";
    public static final String HELPER_DESC = "(L" + TARGET_CLASS + ";Ljava/lang/String;[BII)Ljava/lang/Class;";
    public static final String REDIRECT_OWNER = "github/kasuminova/ssoptimizer/asm/render/RenderThreadRedirector";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(final int opcode,
                                                final String owner,
                                                final String name,
                                                final String descriptor,
                                                final boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && TARGET_CLASS.equals(owner)
                                && "defineClass".equals(name)
                                && DEFINE_CLASS_DESC.equals(descriptor)) {
                            // 原实例调用与合成静态方法的操作数栈布局一致。
                            modified[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, HELPER_METHOD, HELPER_DESC, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (modified[0]) {
                    appendRedirectHelper(cv);
                }
                super.visitEnd();
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private static void appendRedirectHelper(final ClassVisitor cv) {
        final MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HELPER_METHOD,
                HELPER_DESC,
                null,
                null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECT_OWNER, "redirect", "(Ljava/lang/String;[B)[B", false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        final Label callOriginal = new Label();
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, callOriginal);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        mv.visitLabel(callOriginal);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET_CLASS, "defineClass", DEFINE_CLASS_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
