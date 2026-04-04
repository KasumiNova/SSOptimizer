package github.kasuminova.ssoptimizer.asm.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class EngineSpriteProcessorTest {

    /**
     * Builds a minimal {@code com/fs/graphics/Sprite} class with both render methods.
     */
    private byte[] createFakeSpriteClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/fs/graphics/Sprite",
                null, "java/lang/Object", null);

        // render(FF)V — dummy body with a GL11 call
        emitDummyRenderMethod(cw, "render");
        // renderNoBind(FF)V — dummy body with a GL11 call
        emitDummyRenderMethod(cw, "renderNoBind");

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void emitDummyRenderMethod(ClassWriter cw, String name) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "(FF)V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL11", "glPushMatrix", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 3);
        mv.visitEnd();
    }

    @Test
    void renderMethodCallsSpriteRenderHelper() {
        byte[] rewritten = new EngineSpriteProcessor().process(createFakeSpriteClass());
        assertNotNull(rewritten);

        MethodInspection render = inspectMethod(rewritten, "render");
        assertTrue(render.callsHelper,
                "render should call SpriteRenderHelper.renderSprite");
        assertTrue(render.callsTextureBind,
                "render should call TextureObject.bind");
        assertFalse(render.callsGL11,
                "render should not reference GL11 after rewrite");
    }

    @Test
    void renderNoBindMethodCallsSpriteRenderHelperWithoutBind() {
        byte[] rewritten = new EngineSpriteProcessor().process(createFakeSpriteClass());
        assertNotNull(rewritten);

        MethodInspection noBind = inspectMethod(rewritten, "renderNoBind");
        assertTrue(noBind.callsHelper,
                "renderNoBind should call SpriteRenderHelper.renderSprite");
        assertFalse(noBind.callsTextureBind,
                "renderNoBind should NOT call texture bind");
        assertFalse(noBind.callsGL11,
                "renderNoBind should not reference GL11 after rewrite");
    }

    @Test
    void ignoresNonSpriteClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                "com/example/Other", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new EngineSpriteProcessor().process(cw.toByteArray()));
    }

    // ---------- Inspection helpers ----------

    private MethodInspection inspectMethod(byte[] classBytes, String methodName) {
        ClassReader reader = new ClassReader(classBytes);
        boolean[] helper = {false};
        boolean[] texBind = {false};
        boolean[] gl11 = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!methodName.equals(name) || !"(FF)V".equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName,
                                                String mDesc, boolean itf) {
                        if (owner.equals(EngineSpriteProcessor.HELPER_OWNER)
                                && "renderSprite".equals(mName)
                                && EngineSpriteProcessor.RENDER_DESC.equals(mDesc)) {
                            helper[0] = true;
                        }
                        if ("com/fs/graphics/TextureObject".equals(owner)
                                && "bind".equals(mName)) {
                            texBind[0] = true;
                        }
                        if ("org/lwjgl/opengl/GL11".equals(owner)) {
                            gl11[0] = true;
                        }
                    }
                };
            }
        }, 0);

        return new MethodInspection(helper[0], texBind[0], gl11[0]);
    }

    private record MethodInspection(boolean callsHelper, boolean callsTextureBind,
                                    boolean callsGL11) {
    }
}
