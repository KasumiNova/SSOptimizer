package github.kasuminova.ssoptimizer.loading;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import github.kasuminova.ssoptimizer.agent.AsmCommonSuperClassResolver;
import org.objectweb.asm.*;

/**
 * Replaces TextureLoader's hot BufferedImage-to-ByteBuffer conversion with a
 * helper that bulk-reads ARGB rows instead of hammering Raster.getPixel().
 */
public final class TextureLoaderPixelProcessor implements AsmClassProcessor {
    public static final  String TARGET_CLASS             = "com/fs/graphics/TextureLoader";
    public static final  String TARGET_METHOD            = "super";
    public static final  String TARGET_DESC              = "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;";
    public static final  String PUBLIC_LOAD_DESC         = "(Ljava/lang/String;)Lcom/fs/graphics/Object;";
    public static final  String HELPER_OWNER             = "github/kasuminova/ssoptimizer/loading/TexturePixelConverter";
    public static final  String DIMENSION_HELPER_OWNER   = "github/kasuminova/ssoptimizer/loading/TextureDimensionSupport";
    public static final  String IMAGE_READ_HELPER_OWNER  = "github/kasuminova/ssoptimizer/loading/FastResourceImageDecoder";
    public static final  String IMAGE_READ_HELPER_DESC   = "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    public static final  String UPLOAD_HELPER_OWNER      = "github/kasuminova/ssoptimizer/loading/TextureUploadHelper";
    public static final  String LAZY_LOAD_HELPER_OWNER   = "github/kasuminova/ssoptimizer/loading/LazyTextureManager";
    public static final  String LAZY_LOAD_HELPER_METHOD  = "loadTexture";
    public static final  String LAZY_LOAD_HELPER_DESC    = "(Lcom/fs/graphics/TextureLoader;Ljava/util/HashMap;Ljava/lang/String;)Lcom/fs/graphics/Object;";
    private static final String RESULT_OWNER             = "github/kasuminova/ssoptimizer/loading/TexturePixelConversionResult";
    private static final String DIMENSION_DESC           = "(I)I";
    private static final String IMAGE_READ_METHOD        = "String";
    private static final String IMAGE_READ_DESC          = "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String IMAGEIO_OWNER            = "javax/imageio/ImageIO";
    private static final String IMAGEIO_READ_DESC        = "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    private static final String GL11_OWNER               = "org/lwjgl/opengl/GL11";
    private static final String TEXTURE_CACHE_FIELD      = "ÔO0000";
    private static final String TEXTURE_CACHE_DESC       = "Ljava/util/HashMap;";
    private static final String GL_TEX_IMAGE_2D_DESC     = "(IIIIIIIILjava/nio/ByteBuffer;)V";
    private static final String GL_TEX_SUB_IMAGE_2D_DESC = "(IIIIIIIILjava/nio/ByteBuffer;)V";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return AsmCommonSuperClassResolver.resolve(type1, type2);
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String sig, final String[] ex) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(desc)) {
                    modified[0] = true;
                    return new MethodReplacer(delegate);
                }
                if (TARGET_METHOD.equals(name) && PUBLIC_LOAD_DESC.equals(desc)) {
                    modified[0] = true;
                    return new PathLoadMethodReplacer(delegate);
                }
                if (TARGET_METHOD.equals(name) && DIMENSION_DESC.equals(desc)) {
                    modified[0] = true;
                    return new DimensionMethodReplacer(delegate);
                }
                MethodVisitor visitor = delegate;
                if (IMAGE_READ_METHOD.equals(name) && IMAGE_READ_DESC.equals(desc)) {
                    modified[0] = true;
                    visitor = new ImageReadMethodAdapter(delegate);
                }
                return new UploadMethodAdapter(visitor, modified);
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    static final class PathLoadMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        PathLoadMethodReplacer(final MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, TEXTURE_CACHE_FIELD, TEXTURE_CACHE_DESC);
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, LAZY_LOAD_HELPER_OWNER,
                    LAZY_LOAD_HELPER_METHOD, LAZY_LOAD_HELPER_DESC, false);
            target.visitInsn(Opcodes.ARETURN);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }
    }

    static final class MethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        MethodReplacer(final MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            emitBody();
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }

        private void emitBody() {
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "convert", "(Ljava/awt/image/BufferedImage;)L" + RESULT_OWNER + ";", false);
            target.visitVarInsn(Opcodes.ASTORE, 3);

            target.visitVarInsn(Opcodes.ALOAD, 2);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "textureHeight", "()I", false);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/fs/graphics/Object", "Ô00000", "(I)V", false);

            target.visitVarInsn(Opcodes.ALOAD, 2);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "textureWidth", "()I", false);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/fs/graphics/Object", "Object", "(I)V", false);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "upperHalfColor", "()Ljava/awt/Color;", false);
            target.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, "interface", "Ljava/awt/Color;");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "averageColor", "()Ljava/awt/Color;", false);
            target.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, "õ00000", "Ljava/awt/Color;");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "lowerHalfColor", "()Ljava/awt/Color;", false);
            target.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, "Ó00000", "Ljava/awt/Color;");

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "buffer", "()Ljava/nio/ByteBuffer;", false);
            target.visitInsn(Opcodes.ARETURN);
        }
    }

    static final class DimensionMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        DimensionMethodReplacer(final MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitVarInsn(Opcodes.ILOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, DIMENSION_HELPER_OWNER,
                    "textureDimension", DIMENSION_DESC, false);
            target.visitInsn(Opcodes.IRETURN);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }
    }

    static final class ImageReadMethodAdapter extends MethodVisitor {
        ImageReadMethodAdapter(final MethodVisitor target) {
            super(Opcodes.ASM9, target);
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name,
                                    final String descriptor, final boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC
                    && IMAGEIO_OWNER.equals(owner)
                    && "read".equals(name)
                    && IMAGEIO_READ_DESC.equals(descriptor)) {
                visitVarInsn(Opcodes.ALOAD, 1);
                visitInsn(Opcodes.SWAP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, IMAGE_READ_HELPER_OWNER,
                        "decode", IMAGE_READ_HELPER_DESC, false);
                return;
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    static final class UploadMethodAdapter extends MethodVisitor {
        private final boolean[] modified;

        UploadMethodAdapter(final MethodVisitor target, final boolean[] modified) {
            super(Opcodes.ASM9, target);
            this.modified = modified;
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name,
                                    final String descriptor, final boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC
                    && GL11_OWNER.equals(owner)
                    && GL_TEX_IMAGE_2D_DESC.equals(descriptor)
                    && "glTexImage2D".equals(name)) {
                modified[0] = true;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, UPLOAD_HELPER_OWNER,
                        "glTexImage2D", descriptor, false);
                return;
            }

            if (opcode == Opcodes.INVOKESTATIC
                    && GL11_OWNER.equals(owner)
                    && GL_TEX_SUB_IMAGE_2D_DESC.equals(descriptor)
                    && "glTexSubImage2D".equals(name)) {
                modified[0] = true;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, UPLOAD_HELPER_OWNER,
                        "glTexSubImage2D", descriptor, false);
                return;
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}