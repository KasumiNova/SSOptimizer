package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * 在 LWJGL LinuxDisplay 的事件循环中注入 IME 钩子。
 *
 * <p>Mixin 不可行：org.lwjgl 由 RFB 系统类加载器加载（LaunchClassLoader 构造器硬编码
 * {@code org.lwjgl.} 为类加载例外），Launch 域 Mixin 不可达，只能经 SystemAsmBridge 改写。
 * LinuxDisplay 的 getDisplay/getWindow 与 LinuxEvent 类均为包私有，注入字节码在包内取值，
 * 以基本类型传给钩子，钩子不引用 LWJGL 类型。</p>
 */
public final class LinuxDisplayImeProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS       = "org/lwjgl/opengl/LinuxDisplay";
    public static final String HOOK_OWNER         = "github/kasuminova/ssoptimizer/common/input/ime/LinuxDisplayImeHooks";
    public static final String CLEAR_EVENT_METHOD = "ssoptimizer$clearEvent";
    public static final String CLEAR_EVENT_DESC   = "(Lorg/lwjgl/opengl/LinuxEvent;)V";

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
        final boolean[] eventLoopHooked = {false};
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
                if ("setFocused".equals(name) && "(ZI)V".equals(desc)) {
                    modified[0] = true;
                    return new FocusHookMethodVisitor(delegate);
                }
                if ("processEvents".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    eventLoopHooked[0] = true;
                    return new ProcessEventsHookMethodVisitor(delegate);
                }
                return delegate;
            }

            @Override
            public void visitEnd() {
                if (eventLoopHooked[0]) {
                    emitClearEventHelper();
                }
                super.visitEnd();
            }

            private void emitClearEventHelper() {
                final MethodVisitor mv = super.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        CLEAR_EVENT_METHOD, CLEAR_EVENT_DESC, null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.NEW, LINUX_EVENT_OWNER);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LINUX_EVENT_OWNER, "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LINUX_EVENT_OWNER, "copyFrom", CLEAR_EVENT_DESC, false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
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
                visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, "getDisplay", "()J", false);
                visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, "getWindow", "()J", false);
                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "afterCreateKeyboard", "(JJ)V", false);
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
                loadEventBuffer();
                invokeEvent("getType", "()I");
                loadEventBuffer();
                invokeEvent("getKeyAddress", "()J");
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onRawXEvent", "(IJ)Z", false);
                clearEventIfRequested();
            }
            // filterEvent 的结果保留在栈上供原指令的 IFNE 使用，钩子只在其上方压栈并消费自己的返回值。
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && LINUX_EVENT_OWNER.equals(owner)
                    && "filterEvent".equals(name)
                    && "(J)Z".equals(descriptor)) {
                loadEventBuffer();
                invokeEvent("getType", "()I");
                loadEventBuffer();
                invokeEvent("getKeyAddress", "()J");
                loadEventBuffer();
                invokeEvent("getWindow", "()J");
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, "getWindow", "()J", false);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onXEvent", "(IJJJ)Z", false);
                clearEventIfRequested();
            }
        }

        private void loadEventBuffer() {
            visitVarInsn(Opcodes.ALOAD, 0);
            visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, EVENT_BUFFER_FIELD, EVENT_BUFFER_DESC);
        }

        private void invokeEvent(final String name,
                                 final String descriptor) {
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LINUX_EVENT_OWNER, name, descriptor, false);
        }

        private void clearEventIfRequested() {
            final Label skip = new Label();
            visitJumpInsn(Opcodes.IFEQ, skip);
            loadEventBuffer();
            super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, CLEAR_EVENT_METHOD, CLEAR_EVENT_DESC, false);
            visitLabel(skip);
        }
    }
}
