package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * 禁用 LWJGL 自带的 XIM/XIC 生命周期，避免与 SSOptimizer 的输入上下文竞争。
 * org.lwjgl 由系统类加载器加载，Launch 域 Mixin 无法改写。
 */
public final class LinuxKeyboardImeProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/lwjgl/opengl/LinuxKeyboard";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1,
                                                 final String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String desc,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if ("<init>".equals(name)) {
                    modified[0] = true;
                    return new InitMethodVisitor(delegate);
                }
                if ("destroy".equals(name) && "(J)V".equals(desc)) {
                    modified[0] = true;
                    return new DestroyMethodVisitor(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private static final class InitMethodVisitor extends MethodVisitor {
        private InitMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(final int opcode,
                                    final String owner,
                                    final String name,
                                    final String descriptor,
                                    final boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && TARGET_CLASS.equals(owner)) {
                if ("openIM".equals(name) && "(J)J".equals(descriptor)) {
                    visitInsn(Opcodes.POP2);
                    visitInsn(Opcodes.LCONST_0);
                    return;
                }
                if ("createIC".equals(name) && "(JJ)J".equals(descriptor)) {
                    visitInsn(Opcodes.POP2);
                    visitInsn(Opcodes.POP2);
                    visitInsn(Opcodes.LCONST_0);
                    return;
                }
                if ("setupIMEventMask".equals(name) && "(JJJ)V".equals(descriptor)) {
                    visitInsn(Opcodes.POP2);
                    visitInsn(Opcodes.POP2);
                    visitInsn(Opcodes.POP2);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    private static final class DestroyMethodVisitor extends MethodVisitor {
        private DestroyMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(final int opcode,
                                    final String owner,
                                    final String name,
                                    final String descriptor,
                                    final boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && TARGET_CLASS.equals(owner)) {
                if ("destroyIC".equals(name) && "(J)V".equals(descriptor)) {
                    visitInsn(Opcodes.POP2);
                    return;
                }
                if ("closeIM".equals(name) && "(J)V".equals(descriptor)) {
                    visitInsn(Opcodes.POP2);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
