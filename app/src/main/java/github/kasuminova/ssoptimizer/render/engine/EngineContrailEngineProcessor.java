package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * Rewrites the original engine {@code ContrailEngine.new(float)} renderer to
 * preserve per-group texture/blend semantics while replacing immediate-mode
 * segment emission with batched {@code glDrawArrays(GL_QUAD_STRIP)} via
 * {@link ContrailBatchHelper}.
 */
public final class EngineContrailEngineProcessor implements AsmClassProcessor {
    static final String TARGET_CLASS = "com/fs/starfarer/combat/entities/ContrailEngine";
    static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/render/engine/ContrailBatchHelper";

    private static final String GL11_OWNER = "org/lwjgl/opengl/GL11";

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
                if ("new".equals(name) && "(F)V".equals(desc)) {
                    modified[0] = true;
                    return new RenderReplacer(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    static final class RenderReplacer extends MethodVisitor {
        private final MethodVisitor target;

        RenderReplacer(MethodVisitor target) {
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
            // glEnable(GL_TEXTURE_2D = 3553)
            target.visitIntInsn(Opcodes.SIPUSH, 3553);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glEnable", "(I)V", false);

            // glEnable(GL_BLEND = 3042)
            target.visitIntInsn(Opcodes.SIPUSH, 3042);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glEnable", "(I)V", false);

            // ContrailBatchHelper.renderContrails(this.Ò00000, alphaScale)
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, "Ò00000", "Ljava/util/Map;");
            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "renderContrails", "(Ljava/lang/Object;F)V", false);

            target.visitInsn(Opcodes.RETURN);
        }
    }
}