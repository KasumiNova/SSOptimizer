package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;

public final class EngineTexturedStripRendererProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "com/fs/starfarer/renderers/o0OO";
    public static final String TARGET_DESC  = "(Lcom/fs/graphics/Object;FFFFFFLjava/awt/Color;FFFZ)V";
    public static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/render/engine/TexturedStripRenderHelper";
    public static final String HELPER_DESC  = TARGET_DESC;

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
                    return new RenderMethodReplacer(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    static final class RenderMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        RenderMethodReplacer(MethodVisitor target) {
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

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            return target.visitParameterAnnotation(parameter, descriptor, visible);
        }

        private void emitBody() {
            Label returnLabel = new Label();

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitVarInsn(Opcodes.FLOAD, 2);
            target.visitVarInsn(Opcodes.FLOAD, 3);
            target.visitVarInsn(Opcodes.FLOAD, 4);
            target.visitVarInsn(Opcodes.FLOAD, 5);
            target.visitVarInsn(Opcodes.FLOAD, 6);
            target.visitVarInsn(Opcodes.ALOAD, 7);
            target.visitVarInsn(Opcodes.FLOAD, 8);
            target.visitVarInsn(Opcodes.FLOAD, 9);
            target.visitVarInsn(Opcodes.FLOAD, 10);
            target.visitVarInsn(Opcodes.ILOAD, 11);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "renderTexturedStrip", HELPER_DESC, false);
            target.visitLabel(returnLabel);
            target.visitInsn(Opcodes.RETURN);
        }
    }
}