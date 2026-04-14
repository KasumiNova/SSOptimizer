package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * LWJGL {@code WindowsDisplay} 的 IME 生命周期处理器。
 *
 * <p>注入目标：{@code org/lwjgl/opengl/WindowsDisplay}<br>
 * 注入动机：Windows IMM32 后端需要在窗口句柄就绪时 attach，并在焦点变化和键盘销毁时
 * 维护上下文生命周期。LWJGL 2 的 {@code WindowsDisplay} 为包级实现，使用 ASM
 * 可以在精确的方法边界上插入 hook。<br>
 * 注入效果：
 * <ul>
 *   <li>{@code createKeyboard()} 返回前调用 {@code afterCreateKeyboard}</li>
 *   <li>{@code destroyKeyboard()} 返回前调用 {@code beforeDestroyKeyboard}</li>
 *   <li>{@code appActivate(boolean,long)} 入口调用 {@code onFocusChanged}</li>
 *   <li>{@code update()} 入口调用 {@code onUpdate}，用于每帧轮询提交文本</li>
 * </ul>
 * 所有 hook 均委托给
 * {@link github.kasuminova.ssoptimizer.common.input.ime.WindowsDisplayImeHooks}。</p>
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
                    return new ReturnHookMethodVisitor(delegate, "afterCreateKeyboard", "(Ljava/lang/Object;)V");
                }
                if ("destroyKeyboard".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new ReturnHookMethodVisitor(delegate, "beforeDestroyKeyboard", "(Ljava/lang/Object;)V");
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

    private static class ReturnHookMethodVisitor extends MethodVisitor {
        private final String hookMethod;
        private final String hookDesc;

        private ReturnHookMethodVisitor(final MethodVisitor delegate,
                                        final String hookMethod,
                                        final String hookDesc) {
            super(Opcodes.ASM9, delegate);
            this.hookMethod = hookMethod;
            this.hookDesc = hookDesc;
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == Opcodes.RETURN) {
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, hookMethod, hookDesc, false);
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

    /**
     * 在 {@code update()} 方法入口注入 {@code onUpdate()} 调用，
     * 用于每帧轮询 IME 提交文本并分发到聚焦的文本框。
     */
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