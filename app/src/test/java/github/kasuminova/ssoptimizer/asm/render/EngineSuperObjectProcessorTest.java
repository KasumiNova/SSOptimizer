package github.kasuminova.ssoptimizer.asm.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineSuperObjectProcessorTest {
    private byte[] createFakeSuperObjectClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, EngineSuperObjectProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        FieldVisitor fontField = cw.visitField(Opcodes.ACC_PRIVATE, "øÒ0000",
            "Lcom/fs/graphics/super/return;", null, null);
        fontField.visitEnd();
        FieldVisitor requestedFontSize = cw.visitField(Opcodes.ACC_PRIVATE, "ø00000", "F", null, null);
        requestedFontSize.visitEnd();
        FieldVisitor shadowCount = cw.visitField(Opcodes.ACC_PRIVATE, "oo0000", "I", null, null);
        shadowCount.visitEnd();
        FieldVisitor shadowScale = cw.visitField(Opcodes.ACC_PRIVATE, "oO0000", "F", null, null);
        shadowScale.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "o00000",
                EngineSuperObjectProcessor.TARGET_DESC, null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glTexCoord2f", "(FF)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glVertex2f", "(FF)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 6);
        mv.visitEnd();

        MethodVisitor entry = cw.visitMethod(Opcodes.ACC_PUBLIC, EngineSuperObjectProcessor.ENTRY_METHOD,
            EngineSuperObjectProcessor.ENTRY_DESC, null, null);
        entry.visitCode();
        entry.visitInsn(Opcodes.RETURN);
        entry.visitMaxs(0, 1);
        entry.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void rewritesGlyphMethodToHelper() {
        byte[] rewritten = new EngineSuperObjectProcessor().process(createFakeSuperObjectClass());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] foundHelper = {false};
        final boolean[] foundLayoutHelper = {false};
        final boolean[] foundFontSwapHelper = {false};
        final boolean[] foundFontSizeAdjustHelper = {false};
        final boolean[] foundGlVertex = {false};
        final boolean[] foundGlTexCoord = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        if (owner.equals(EngineSuperObjectProcessor.HELPER_OWNER)
                                && "renderGlyphQuad".equals(methodName)
                                && EngineSuperObjectProcessor.HELPER_DESC.equals(methodDesc)) {
                            foundHelper[0] = true;
                        }
                        if (owner.equals(EngineSuperObjectProcessor.LAYOUT_HELPER_OWNER)
                                && "recordGlyphLayout".equals(methodName)
                                && EngineSuperObjectProcessor.LAYOUT_HELPER_DESC.equals(methodDesc)) {
                            foundLayoutHelper[0] = true;
                        }
                        if (owner.equals(EngineSuperObjectProcessor.FONT_SWAP_HELPER_OWNER)
                                && "resolveScaledFont".equals(methodName)
                                && EngineSuperObjectProcessor.FONT_SWAP_HELPER_DESC.equals(methodDesc)) {
                            foundFontSwapHelper[0] = true;
                        }
                        if (owner.equals(EngineSuperObjectProcessor.FONT_SWAP_HELPER_OWNER)
                                && "adjustRequestedFontSize".equals(methodName)
                                && EngineSuperObjectProcessor.FONT_SIZE_ADJUST_HELPER_DESC.equals(methodDesc)) {
                            foundFontSizeAdjustHelper[0] = true;
                        }
                        if (owner.equals("org/lwjgl/opengl/GL11") && "glVertex2f".equals(methodName)) {
                            foundGlVertex[0] = true;
                        }
                        if (owner.equals("org/lwjgl/opengl/GL11") && "glTexCoord2f".equals(methodName)) {
                            foundGlTexCoord[0] = true;
                        }
                    }
                };
            }
        }, 0);

    assertTrue(foundLayoutHelper[0], "super.Object glyph renderer should record phase-2 text layout diagnostics before quad emission");
        assertTrue(foundHelper[0], "super.Object glyph renderer should delegate quad emission to SuperObjectRenderHelper");
        assertTrue(foundFontSwapHelper[0], "super.Object render entry should resolve a scale-aware runtime font before binding and layout");
        assertTrue(foundFontSizeAdjustHelper[0], "super.Object render entry should normalize requested font size after binding a higher-resolution font");
        assertFalse(foundGlVertex[0], "super.Object glyph renderer should not keep direct glVertex2f calls");
        assertFalse(foundGlTexCoord[0], "super.Object glyph renderer should not keep direct glTexCoord2f calls");
    }

    @Test
    void ignoresNonTargetClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                "com/example/Other", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new EngineSuperObjectProcessor().process(cw.toByteArray()));
    }
}