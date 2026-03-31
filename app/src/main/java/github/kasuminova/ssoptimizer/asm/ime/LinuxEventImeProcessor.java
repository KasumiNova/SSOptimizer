package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM processor that replaces {@code LinuxEvent.filterEvent(J)Z} so that it
 * <b>no longer</b> calls the native {@code nFilterEvent} (which invokes
 * {@code XFilterEvent}).  The replacement method simply returns {@code false},
 * telling the caller that the event was <em>not</em> consumed.
 *
 * <p>This ensures that SSOptimizer's native code is the sole caller of
 * {@code XFilterEvent} using its own XIC.  Without this patch LWJGL would
 * call {@code XFilterEvent} via {@code nFilterEvent} and the IM server
 * (e.g.&nbsp;fcitx) would see every event twice — once from LWJGL's call
 * and once from SSOptimizer's — causing broken composing state.
 */
public final class LinuxEventImeProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/lwjgl/opengl/LinuxEvent";

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
                if ("filterEvent".equals(name) && "(J)Z".equals(desc)) {
                    modified[0] = true;
                    return new FilterEventReplacer(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    /**
     * Replaces the entire body of {@code filterEvent(J)Z} with
     * {@code return false;}.  The original body calls
     * {@code nFilterEvent(event_buffer, display)} which triggers
     * {@code XFilterEvent} — we skip that entirely.
     */
    private static final class FilterEventReplacer extends MethodVisitor {
        private FilterEventReplacer(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // Replace the entire method body: return false
            super.visitInsn(Opcodes.ICONST_0);
            super.visitInsn(Opcodes.IRETURN);
        }

        // Suppress all original bytecodes and debug info — visitCode
        // already emitted the replacement body.  We still need to let
        // visitMaxs/visitEnd through for the ClassWriter.
        @Override
        public void visitInsn(final int opcode) {
            // drop original instructions
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            // drop
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner,
                                   final String name, final String descriptor) {
            // drop
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner,
                                    final String name, final String descriptor,
                                    final boolean isInterface) {
            // drop
        }

        @Override
        public void visitLineNumber(final int line, final org.objectweb.asm.Label start) {
            // drop — original line numbers reference PCs that no longer exist
        }

        @Override
        public void visitLocalVariable(final String name, final String descriptor,
                                       final String signature,
                                       final org.objectweb.asm.Label start,
                                       final org.objectweb.asm.Label end,
                                       final int index) {
            // drop — original local variable entries reference removed labels
        }

        @Override
        public void visitLabel(final org.objectweb.asm.Label label) {
            // drop — original labels reference removed code
        }

        @Override
        public void visitFrame(final int type, final int numLocal,
                               final Object[] local, final int numStack,
                               final Object[] stack) {
            // drop
        }

        @Override
        public void visitJumpInsn(final int opcode, final org.objectweb.asm.Label label) {
            // drop
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            // drop
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            // drop
        }

        @Override
        public void visitLdcInsn(final Object value) {
            // drop
        }
    }
}
