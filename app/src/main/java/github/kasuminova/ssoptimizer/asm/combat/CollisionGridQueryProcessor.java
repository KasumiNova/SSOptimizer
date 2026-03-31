package github.kasuminova.ssoptimizer.asm.combat;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import org.objectweb.asm.*;

public final class CollisionGridQueryProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS  = "com/fs/starfarer/combat/o0OO/oOoO";
    public static final String TARGET_METHOD = "getCheckIterator";
    public static final String TARGET_DESC   = "(Lorg/lwjgl/util/vector/Vector2f;FF)Ljava/util/Iterator;";
    public static final String HELPER_OWNER  =
            "github/kasuminova/ssoptimizer/common/combat/ai/grid/CollisionGridQueryHelper";
    public static final String HELPER_DESC   = "([[Ljava/util/List;IIIIFFFFF)Ljava/util/Iterator;";

    private static final String VECTOR2F_CLASS = "org/lwjgl/util/vector/Vector2f";

    private static final String FIELD_CELLS       = "class";
    private static final String FIELD_GRID_WIDTH  = "\u00D300000";
    private static final String FIELD_GRID_HEIGHT = "float";
    private static final String FIELD_BASE_X      = "o00000";
    private static final String FIELD_BASE_Y      = "\u00F500000";
    private static final String FIELD_CELL_SIZE   = "new";

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
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(desc)) {
                    modified[0] = true;
                    return new QueryMethodReplacer(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    static final class QueryMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        QueryMethodReplacer(MethodVisitor target) {
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
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, FIELD_CELLS, "[[Ljava/util/List;");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, FIELD_GRID_WIDTH, "I");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, FIELD_GRID_HEIGHT, "I");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, FIELD_BASE_X, "I");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, FIELD_BASE_Y, "I");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, FIELD_CELL_SIZE, "F");

            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitFieldInsn(Opcodes.GETFIELD, VECTOR2F_CLASS, "x", "F");

            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitFieldInsn(Opcodes.GETFIELD, VECTOR2F_CLASS, "y", "F");

            target.visitVarInsn(Opcodes.FLOAD, 2);
            target.visitVarInsn(Opcodes.FLOAD, 3);

            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    TARGET_METHOD, HELPER_DESC, false);
            target.visitInsn(Opcodes.ARETURN);
        }
    }
}