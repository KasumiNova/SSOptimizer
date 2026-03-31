package github.kasuminova.ssoptimizer.loading;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * Upgrades the base game's deferred image loader from one worker thread to a
 * configurable worker group, while preserving the original queue/result maps.
 */
public final class ParallelImagePreloadProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS        = "com/fs/graphics/L";
    public static final String HELPER_OWNER        = "github/kasuminova/ssoptimizer/loading/ParallelImagePreloadCoordinator";
    public static final String DECODE_HELPER_OWNER = "github/kasuminova/ssoptimizer/loading/FastResourceImageDecoder";
    public static final String DECODE_HELPER_DESC  = "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    public static final String QUEUE_HELPER_OWNER  = "github/kasuminova/ssoptimizer/loading/ParallelImagePreloadQueueTracker";

    private static final String START_METHOD         = "o00000";
    private static final String IMAGE_DECODE_METHOD  = "o00000";
    private static final String SHUTDOWN_METHOD      = "new";
    private static final String AWAIT_BYTES_METHOD   = "new";
    private static final String AWAIT_BYTES_DESC     = "(Ljava/lang/String;)[B";
    private static final String AWAIT_IMAGE_METHOD   = "class";
    private static final String AWAIT_IMAGE_DESC     = "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String ENQUEUE_IMAGE_METHOD = "Ö00000";
    private static final String ENQUEUE_BYTES_METHOD = "Ó00000";
    private static final String ENQUEUE_DESC         = "(Ljava/lang/String;)V";
    private static final String NO_ARGS_VOID         = "()V";
    private static final String IMAGE_DECODE_DESC    = "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String IMAGEIO_OWNER        = "javax/imageio/ImageIO";
    private static final String IMAGEIO_READ_DESC    = "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    private static final String LIST_OWNER           = "java/util/List";
    private static final String MAP_OWNER            = "java/util/Map";
    private static final String IMAGE_QUEUE_FIELD    = "class";
    private static final String IMAGE_RESULT_FIELD   = "Ø00000";
    private static final String IMAGE_SENTINEL_FIELD = "Ô00000";
    private static final String BYTE_QUEUE_FIELD     = "õ00000";
    private static final String BYTE_RESULT_FIELD    = "new";
    private static final String BYTE_SENTINEL_FIELD  = "Ö00000";
    private static final String IMAGE_SENTINEL_DESC  = "Ljava/awt/image/BufferedImage;";
    private static final String BYTE_SENTINEL_DESC   = "[B";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (START_METHOD.equals(name) && NO_ARGS_VOID.equals(desc)) {
                    modified[0] = true;
                    return new StartMethodReplacer(delegate);
                }
                if (SHUTDOWN_METHOD.equals(name) && NO_ARGS_VOID.equals(desc)) {
                    modified[0] = true;
                    return new ShutdownMethodReplacer(delegate);
                }
                if (AWAIT_BYTES_METHOD.equals(name) && AWAIT_BYTES_DESC.equals(desc)) {
                    modified[0] = true;
                    return new AwaitBytesMethodReplacer(delegate);
                }
                if (AWAIT_IMAGE_METHOD.equals(name) && AWAIT_IMAGE_DESC.equals(desc)) {
                    modified[0] = true;
                    return new AwaitImageMethodReplacer(delegate);
                }
                if (ENQUEUE_IMAGE_METHOD.equals(name) && ENQUEUE_DESC.equals(desc)) {
                    modified[0] = true;
                    return new EnqueueImageMethodReplacer(delegate);
                }
                if (ENQUEUE_BYTES_METHOD.equals(name) && ENQUEUE_DESC.equals(desc)) {
                    modified[0] = true;
                    return new EnqueueBytesMethodReplacer(delegate);
                }
                if (IMAGE_DECODE_METHOD.equals(name) && IMAGE_DECODE_DESC.equals(desc)) {
                    modified[0] = true;
                    return new DecodeMethodAdapter(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private static final class StartMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        private StartMethodReplacer(MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "startWorkers", NO_ARGS_VOID, false);
            target.visitInsn(Opcodes.RETURN);
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
    }

    private static final class ShutdownMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        private ShutdownMethodReplacer(MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "stopWorkers", NO_ARGS_VOID, false);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, QUEUE_HELPER_OWNER, "clearPending", NO_ARGS_VOID, false);

            clearCollection("Ø00000", "Ljava/util/Map;", "java/util/Map");
            clearCollection("new", "Ljava/util/Map;", "java/util/Map");
            clearCollection("class", "Ljava/util/List;", "java/util/List");
            clearCollection("õ00000", "Ljava/util/List;", "java/util/List");

            target.visitInsn(Opcodes.RETURN);
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

        private void clearCollection(String fieldName, String fieldDesc, String owner) {
            target.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, fieldName, fieldDesc);
            target.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, "clear", NO_ARGS_VOID, true);
        }
    }

    private abstract static class BodyReplacingMethodVisitor extends MethodVisitor {
        protected final MethodVisitor target;

        private BodyReplacingMethodVisitor(final MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public final void visitCode() {
            target.visitCode();
            emitBody();
        }

        @Override
        public final void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public final void visitEnd() {
            target.visitEnd();
        }

        @Override
        public final AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }

        protected abstract void emitBody();
    }

    private static final class AwaitBytesMethodReplacer extends BodyReplacingMethodVisitor {
        private AwaitBytesMethodReplacer(final MethodVisitor target) {
            super(target);
        }

        @Override
        protected void emitBody() {
            target.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, BYTE_RESULT_FIELD, "Ljava/util/Map;");
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, BYTE_SENTINEL_FIELD, BYTE_SENTINEL_DESC);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, QUEUE_HELPER_OWNER,
                    "awaitBytes", "(Ljava/util/Map;Ljava/lang/String;[B)[B", false);
            target.visitInsn(Opcodes.ARETURN);
        }
    }

    private static final class AwaitImageMethodReplacer extends BodyReplacingMethodVisitor {
        private AwaitImageMethodReplacer(final MethodVisitor target) {
            super(target);
        }

        @Override
        protected void emitBody() {
            target.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, IMAGE_RESULT_FIELD, "Ljava/util/Map;");
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, IMAGE_SENTINEL_FIELD, IMAGE_SENTINEL_DESC);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, QUEUE_HELPER_OWNER,
                    "awaitImage", "(Ljava/util/Map;Ljava/lang/String;Ljava/awt/image/BufferedImage;)Ljava/awt/image/BufferedImage;", false);
            target.visitInsn(Opcodes.ARETURN);
        }
    }

    private static final class EnqueueImageMethodReplacer extends BodyReplacingMethodVisitor {
        private EnqueueImageMethodReplacer(final MethodVisitor target) {
            super(target);
        }

        @Override
        protected void emitBody() {
            target.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, IMAGE_QUEUE_FIELD, "Ljava/util/List;");
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, QUEUE_HELPER_OWNER,
                    "enqueueImage", "(Ljava/util/List;Ljava/lang/String;)V", false);
            target.visitInsn(Opcodes.RETURN);
        }
    }

    private static final class EnqueueBytesMethodReplacer extends BodyReplacingMethodVisitor {
        private EnqueueBytesMethodReplacer(final MethodVisitor target) {
            super(target);
        }

        @Override
        protected void emitBody() {
            target.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, BYTE_QUEUE_FIELD, "Ljava/util/List;");
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, QUEUE_HELPER_OWNER,
                    "enqueueBytes", "(Ljava/util/List;Ljava/lang/String;)V", false);
            target.visitInsn(Opcodes.RETURN);
        }
    }

    private static final class DecodeMethodAdapter extends MethodVisitor {
        private DecodeMethodAdapter(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC
                    && IMAGEIO_OWNER.equals(owner)
                    && "read".equals(name)
                    && IMAGEIO_READ_DESC.equals(descriptor)) {
                visitVarInsn(Opcodes.ALOAD, 0);
                visitInsn(Opcodes.SWAP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, DECODE_HELPER_OWNER,
                        "decode", DECODE_HELPER_DESC, false);
                return;
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}