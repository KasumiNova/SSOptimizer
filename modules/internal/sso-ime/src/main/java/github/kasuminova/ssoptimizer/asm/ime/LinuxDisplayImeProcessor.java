package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * 在 LWJGL LinuxDisplay 的事件循环中注入 IME 钩子。
 * org.lwjgl 由系统类加载器加载，Launch 域 Mixin 无法改写。
 */
public final class LinuxDisplayImeProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/lwjgl/opengl/LinuxDisplay";
    public static final String HOOK_OWNER   = "github/kasuminova/ssoptimizer/common/input/ime/LinuxDisplayImeHooks";

    private static final String LINUX_EVENT_OWNER  = "org/lwjgl/opengl/LinuxEvent";
    private static final String EVENT_BUFFER_FIELD = "event_buffer";
    private static final String EVENT_BUFFER_DESC  = "Lorg/lwjgl/opengl/LinuxEvent;";

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
                if ("setFocused".equals(name) && "(ZI)V".equals(desc)) {
                    modified[0] = true;
                    return new FocusHookMethodVisitor(delegate);
                }
                if ("processEvents".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new ProcessEventsHookMethodVisitor(delegate);
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

    private static final class ProcessEventsHookMethodVisitor extends MethodVisitor {
        private ProcessEventsHookMethodVisitor(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(final int opcode,
                                    final String owner,
                                    final String name,
                                    final String descriptor,
                                    final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            // 在 LWJGL 窗口 ID 校验前处理 XIM 协议事件，避免 ClientMessage 被跳过。
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && LINUX_EVENT_OWNER.equals(owner)
                    && "nextEvent".equals(name)
                    && "(J)V".equals(descriptor)) {
                visitVarInsn(Opcodes.ALOAD, 0);
                visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, EVENT_BUFFER_FIELD, EVENT_BUFFER_DESC);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onRawXEvent",
                        "(Ljava/lang/Object;)V", false);
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && LINUX_EVENT_OWNER.equals(owner)
                    && "filterEvent".equals(name)
                    && "(J)Z".equals(descriptor)) {
                // 调整参数栈序，并为 IFNE 分支保留原始 filterResult。
                visitInsn(Opcodes.DUP);
                visitVarInsn(Opcodes.ALOAD, 0);
                visitInsn(Opcodes.SWAP);
                visitVarInsn(Opcodes.ALOAD, 0);
                visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, EVENT_BUFFER_FIELD, EVENT_BUFFER_DESC);
                visitInsn(Opcodes.SWAP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onXEvent",
                        "(Ljava/lang/Object;Ljava/lang/Object;Z)V", false);
            }
        }
    }
}
