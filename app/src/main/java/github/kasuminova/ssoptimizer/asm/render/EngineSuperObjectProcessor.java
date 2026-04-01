package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * Rewrites {@code com.fs.graphics.super.Object.o00000(FFL...;FZ)V} so one glyph
 * quad emission becomes a single helper/native call instead of four
 * {@code glTexCoord2f} + four {@code glVertex2f} Java calls.
 */
public final class EngineSuperObjectProcessor implements AsmClassProcessor {
    static final String TARGET_CLASS                 = "com/fs/graphics/super/Object";
    static final String GLYPH_CLASS                  = "com/fs/graphics/super/oOOO";
    static final String FONT_CLASS                   = "com/fs/graphics/super/return";
    static final String ENTRY_METHOD                 = "Õ00000";
    static final String ENTRY_DESC                   = "()V";
    static final String HELPER_OWNER                 =
            "github/kasuminova/ssoptimizer/common/render/engine/SuperObjectRenderHelper";
    static final String LAYOUT_HELPER_OWNER          =
            "github/kasuminova/ssoptimizer/common/render/engine/TextLayoutDiagnostics";
    static final String FONT_SWAP_HELPER_OWNER       =
            "github/kasuminova/ssoptimizer/common/font/RuntimeScaledFontCache";
    static final String TARGET_DESC                  = "(FFLcom/fs/graphics/super/oOOO;FZ)V";
    static final String HELPER_DESC                  = "(FFIIIFFFFFIF)V";
    static final String LAYOUT_HELPER_DESC           = "(IIIIFFII)V";
    static final String FONT_SWAP_HELPER_DESC        = "(Ljava/lang/Object;F)Ljava/lang/Object;";
    static final String FONT_SIZE_ADJUST_HELPER_DESC = "(Ljava/lang/Object;F)F";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if ("o00000".equals(name) && TARGET_DESC.equals(desc)) {
                    modified[0] = true;
                    return new RenderGlyphMethodReplacer(delegate);
                }
                if (ENTRY_METHOD.equals(name) && ENTRY_DESC.equals(desc)) {
                    modified[0] = true;
                    return new RenderEntryMethodAdapter(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    static final class RenderGlyphMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        RenderGlyphMethodReplacer(MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            emitBody();
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }

        private void emitBody() {
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "Ö00000", "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "if", "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "void", "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "øÒ0000", "Lcom/fs/graphics/super/return;");
            target.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false);

            target.visitVarInsn(Opcodes.FLOAD, 4);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "ø00000", "F");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "øÒ0000", "Lcom/fs/graphics/super/return;");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FONT_CLASS, "class", "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "øÒ0000", "Lcom/fs/graphics/super/return;");
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FONT_CLASS, "float", "()I", false);

            target.visitMethodInsn(Opcodes.INVOKESTATIC, LAYOUT_HELPER_OWNER,
                    "recordGlyphLayout", LAYOUT_HELPER_DESC, false);

            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitVarInsn(Opcodes.FLOAD, 2);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "ø00000", "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "ÒO0000", "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "Ò00000", "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "õ00000", "()F", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "do", "()F", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "String", "()F", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, "ô00000", "()F", false);

            target.visitVarInsn(Opcodes.FLOAD, 4);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "oo0000", "I");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "oO0000", "F");

            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "renderGlyphQuad", HELPER_DESC, false);
            target.visitInsn(Opcodes.RETURN);
        }
    }

    static final class RenderEntryMethodAdapter extends MethodVisitor {
        RenderEntryMethodAdapter(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "øÒ0000", "Lcom/fs/graphics/super/return;");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "ø00000", "F");
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    FONT_SWAP_HELPER_OWNER,
                    "resolveScaledFont",
                    FONT_SWAP_HELPER_DESC,
                    false);
            super.visitTypeInsn(Opcodes.CHECKCAST, FONT_CLASS);
            super.visitInsn(Opcodes.DUP_X1);
            super.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, "øÒ0000", "Lcom/fs/graphics/super/return;");

            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitInsn(Opcodes.SWAP);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "ø00000", "F");
            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                    FONT_SWAP_HELPER_OWNER,
                    "adjustRequestedFontSize",
                    FONT_SIZE_ADJUST_HELPER_DESC,
                    false);
            super.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, "ø00000", "F");
        }
    }
}