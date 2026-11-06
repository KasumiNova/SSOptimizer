package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * 在 LWJGL WindowsDisplay 中注入 IME 生命周期钩子。
 *
 * <p>Mixin 不可行：org.lwjgl 由 RFB 系统类加载器加载（LaunchClassLoader 构造器硬编码
 * {@code org.lwjgl.} 为类加载例外），Launch 域 Mixin 不可达，只能经 SystemAsmBridge 改写。
 * hwnd 为私有字段，由注入字节码在类内读取后传给钩子。</p>
 */
public final class WindowsDisplayImeProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/lwjgl/opengl/WindowsDisplay";
    public static final String HOOK_OWNER = "github/kasuminova/ssoptimizer/common/input/ime/WindowsDisplayImeHooks";

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
                if ("createKeyboard".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new CreateKeyboardHookMethodVisitor(delegate);
                }
                if ("destroyKeyboard".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new DestroyKeyboardHookMethodVisitor(delegate);
                }
                if ("appActivate".equals(name) && "(ZJ)V".equals(desc)) {
                    modified[0] = true;
                    return new FocusHookMethodVisitor(delegate);
                }
                if ("update".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new UpdateHookMethodVisitor(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private static final class CreateKeyboardHookMethodVisitor extends MethodVisitor {
        private CreateKeyboardHookMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) {
                visitVarInsn(Opcodes.ALOAD, 0);
                visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "hwnd", "J");
                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "afterCreateKeyboard", "(J)V", false);
            }
            super.visitInsn(opcode);
        }
    }

    private static final class DestroyKeyboardHookMethodVisitor extends MethodVisitor {
        private DestroyKeyboardHookMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) {
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "beforeDestroyKeyboard", "(Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
    }

    private static final class FocusHookMethodVisitor extends MethodVisitor {
        private FocusHookMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            visitVarInsn(Opcodes.ALOAD, 0);
            visitVarInsn(Opcodes.ILOAD, 1);
            visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onFocusChanged", "(Ljava/lang/Object;Z)V", false);
        }
    }

    private static final class UpdateHookMethodVisitor extends MethodVisitor {
        private UpdateHookMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onUpdate", "()V", false);
        }
    }
}
