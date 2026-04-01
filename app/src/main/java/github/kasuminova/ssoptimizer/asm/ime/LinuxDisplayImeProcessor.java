package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * LWJGL {@code LinuxDisplay} 的 IME 相关 ASM 处理器。
 *
 * <p>注入目标：{@code org/lwjgl/opengl/LinuxDisplay} 的多个方法<br>
 * 注入动机：需要在 LWJGL 的事件循环中插入多个 IME Hook：
 * <ul>
 *   <li>{@code createKeyboard / destroyKeyboard}：键盘创建/销毁时通知 IME 服务</li>
 *   <li>{@code setFocused}：捕获焦点变化以驱动 {@code focusIn/focusOut}</li>
 *   <li>{@code processEvents}：在 {@code nextEvent()} 后注入 {@code onRawXEvent} 钩子
 *       （用于拦截 XIM 协议 ClientMessage 等非键盘事件），以及在 {@code filterEvent()} 后
 *       注入 {@code onXEvent} 钩子（传递过滤结果给 IME 处理）</li>
 * </ul>
 * Mixin 无法 Hook LWJGL 内部包级方法和精确控制方法内的插入位置。<br>
 * 注入效果：所有 Hook 委托给 {@link github.kasuminova.ssoptimizer.common.input.ime.LinuxDisplayImeHooks}。</p>
 */
public final class LinuxDisplayImeProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/lwjgl/opengl/LinuxDisplay";
    public static final String HOOK_OWNER = "github/kasuminova/ssoptimizer/common/input/ime/LinuxDisplayImeHooks";

    private static final String LINUX_EVENT_OWNER = "org/lwjgl/opengl/LinuxEvent";
    private static final String EVENT_BUFFER_FIELD = "event_buffer";
    private static final String EVENT_BUFFER_DESC = "Lorg/lwjgl/opengl/LinuxEvent;";

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
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, hookMethod, hookDesc, false);
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
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ILOAD, 1);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onFocusChanged", "(Ljava/lang/Object;Z)V", false);
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
            // After nextEvent() returns, inject onRawXEvent to process XIM
            // protocol events (especially ClientMessage type=33) that LWJGL's
            // window check would skip before reaching filterEvent.
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && LINUX_EVENT_OWNER.equals(owner)
                    && "nextEvent".equals(name)
                    && "(J)V".equals(descriptor)) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, EVENT_BUFFER_FIELD, EVENT_BUFFER_DESC);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onRawXEvent",
                        "(Ljava/lang/Object;)V", false);
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && LINUX_EVENT_OWNER.equals(owner)
                    && "filterEvent".equals(name)
                    && "(J)Z".equals(descriptor)) {
                // Stack: [... filterResult(int/boolean)]
                // DUP the filter result so we can pass it to onXEvent while
                // leaving the original on the stack for the IFNE branch.
                super.visitInsn(Opcodes.DUP);
                // Stack: [... filterResult, filterResult]
                super.visitVarInsn(Opcodes.ALOAD, 0);
                // Stack: [... filterResult, filterResult, linuxDisplay]
                super.visitInsn(Opcodes.SWAP);
                // Stack: [... filterResult, linuxDisplay, filterResult]
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, EVENT_BUFFER_FIELD, EVENT_BUFFER_DESC);
                // Stack: [... filterResult, linuxDisplay, filterResult, linuxEvent]
                super.visitInsn(Opcodes.SWAP);
                // Stack: [... filterResult, linuxDisplay, linuxEvent, filterResult]
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "onXEvent",
                        "(Ljava/lang/Object;Ljava/lang/Object;Z)V", false);
                // Stack: [... filterResult]  — ready for IFNE
            }
        }
    }
}
