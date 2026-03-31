package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM processor that prevents LWJGL's {@code LinuxKeyboard} from creating its
 * own XIM/XIC pair.  By making the native {@code openIM}/{@code createIC} calls
 * return 0, the only XIC registered for the game window belongs to SSOptimizer.
 * This ensures that {@code XFilterEvent} dispatches every event to SSOptimizer's
 * XIC, allowing the input method (e.g.&nbsp;fcitx) to work correctly.
 *
 * <p>In the constructor ({@code <init>}), calls to:
 * <ul>
 *   <li>{@code openIM(J)J}           &rarr; replaced with constant 0L</li>
 *   <li>{@code createIC(JJ)J}        &rarr; replaced with constant 0L</li>
 *   <li>{@code setupIMEventMask(JJJ)V} &rarr; replaced with pop of three longs (no-op)</li>
 * </ul>
 *
 * <p>In {@code destroy(J)V}, calls to:
 * <ul>
 *   <li>{@code destroyIC(J)V}  &rarr; replaced with pop of one long (no-op)</li>
 *   <li>{@code closeIM(J)V}    &rarr; replaced with pop of one long (no-op)</li>
 * </ul>
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

    /**
     * Hooks the constructor to replace openIM/createIC/setupIMEventMask with no-ops.
     */
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
                    // Stack: [display(long)] -> pop display, push 0L
                    super.visitInsn(Opcodes.POP2);
                    super.visitInsn(Opcodes.LCONST_0);
                    return;
                }
                if ("createIC".equals(name) && "(JJ)J".equals(descriptor)) {
                    // Stack: [xim(long), window(long)] -> pop both, push 0L
                    super.visitInsn(Opcodes.POP2); // pop window
                    super.visitInsn(Opcodes.POP2); // pop xim
                    super.visitInsn(Opcodes.LCONST_0);
                    return;
                }
                if ("setupIMEventMask".equals(name) && "(JJJ)V".equals(descriptor)) {
                    // Stack: [display(long), window(long), xic(long)] -> pop all three
                    super.visitInsn(Opcodes.POP2); // pop xic
                    super.visitInsn(Opcodes.POP2); // pop window
                    super.visitInsn(Opcodes.POP2); // pop display
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    /**
     * Hooks destroy() to replace destroyIC/closeIM with no-ops.
     */
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
                    // Stack: [xic(long)] -> pop
                    super.visitInsn(Opcodes.POP2);
                    return;
                }
                if ("closeIM".equals(name) && "(J)V".equals(descriptor)) {
                    // Stack: [xim(long)] -> pop
                    super.visitInsn(Opcodes.POP2);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
