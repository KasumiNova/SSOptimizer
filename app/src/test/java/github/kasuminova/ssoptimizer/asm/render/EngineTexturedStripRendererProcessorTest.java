package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class EngineTexturedStripRendererProcessorTest {

    private byte[] createFakeRendererClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, EngineTexturedStripRendererProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            GameMemberNames.TexturedStripRenderer.RENDER_TEXTURED_STRIP,
            EngineTexturedStripRendererProcessor.TARGET_DESC,
            null,
            null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.SIPUSH, 3042);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL11", "glEnable", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 12);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void targetMethodCallsTexturedStripHelper() {
        byte[] rewritten = new EngineTexturedStripRendererProcessor().process(createFakeRendererClass());
        assertNotNull(rewritten);

        MethodInspection inspection = inspectMethod(rewritten);
        assertTrue(inspection.callsHelper,
            "renderTexturedStrip should call TexturedStripRenderHelper.renderTexturedStrip");
        assertFalse(inspection.callsGL11,
            "renderTexturedStrip should not reference GL11 after rewrite");
    }

    @Test
    void ignoresNonTargetClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                "com/example/OtherRenderer", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new EngineTexturedStripRendererProcessor().process(cw.toByteArray()));
    }

    private MethodInspection inspectMethod(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        boolean[] helper = {false};
        boolean[] gl11 = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!GameMemberNames.TexturedStripRenderer.RENDER_TEXTURED_STRIP.equals(name)
                        || !EngineTexturedStripRendererProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        if (owner.equals(EngineTexturedStripRendererProcessor.HELPER_OWNER)
                                && "renderTexturedStrip".equals(methodName)
                                && EngineTexturedStripRendererProcessor.HELPER_DESC.equals(methodDesc)) {
                            helper[0] = true;
                        }
                        if ("org/lwjgl/opengl/GL11".equals(owner)) {
                            gl11[0] = true;
                        }
                    }
                };
            }
        }, 0);

        return new MethodInspection(helper[0], gl11[0]);
    }

    private record MethodInspection(boolean callsHelper, boolean callsGL11) {
    }
}