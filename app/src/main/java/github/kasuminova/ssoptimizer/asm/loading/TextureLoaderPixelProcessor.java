package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;

/**
 * 纹理像素转换处理器。
 * <p>
 * 注入目标：{@code com.fs.graphics.TextureLoader}<br>
 * 注入动机：原版把 {@link java.awt.image.BufferedImage} 转为上传缓冲区时频繁调用
 * Raster API，CPU 开销较高；同时还需要接入延迟纹理加载和尺寸修正逻辑。<br>
 * 注入效果：替换像素转换、路径加载、尺寸计算与部分上传调用，统一走优化 helper。
 */
public final class TextureLoaderPixelProcessor implements AsmClassProcessor {
    public static final  String TARGET_CLASS             = GameClassNames.TEXTURE_LOADER;
    public static final  String TARGET_METHOD            = GameMemberNames.TextureLoader.CONVERT_PIXELS;
    public static final  String TARGET_DESC              = "(Ljava/awt/image/BufferedImage;L" + GameClassNames.TEXTURE_OBJECT + ";)Ljava/nio/ByteBuffer;";
    public static final  String PUBLIC_LOAD_METHOD       = GameMemberNames.TextureLoader.LOAD_TEXTURE;
    public static final  String PUBLIC_LOAD_DESC         = "(Ljava/lang/String;)L" + GameClassNames.TEXTURE_OBJECT + ";";
    public static final  String ORIGINAL_LOAD_METHOD     = "ssoptimizer$loadTextureEager";
    public static final  String DIMENSION_METHOD         = GameMemberNames.TextureLoader.TEXTURE_DIMENSION;
    public static final  String HELPER_OWNER             = "github/kasuminova/ssoptimizer/common/loading/TexturePixelConverter";
    public static final  String DIMENSION_HELPER_OWNER   = "github/kasuminova/ssoptimizer/common/loading/TextureDimensionSupport";
    public static final  String IMAGE_READ_HELPER_OWNER  = "github/kasuminova/ssoptimizer/common/loading/FastResourceImageDecoder";
    public static final  String IMAGE_READ_HELPER_DESC   = "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    public static final  String UPLOAD_HELPER_OWNER      = "github/kasuminova/ssoptimizer/common/loading/TextureUploadHelper";
    public static final  String LAZY_LOAD_HELPER_OWNER   = "github/kasuminova/ssoptimizer/common/loading/LazyTextureManager";
    public static final  String LAZY_LOAD_HELPER_METHOD  = "loadTexture";
    public static final  String LAZY_LOAD_HELPER_DESC    = "(L" + GameClassNames.TEXTURE_LOADER + ";Ljava/util/HashMap;Ljava/lang/String;)L" + GameClassNames.TEXTURE_OBJECT + ";";
    private static final String RESULT_OWNER             = "github/kasuminova/ssoptimizer/common/loading/TexturePixelConversionResult";
    private static final String DIMENSION_DESC           = "(I)I";
    private static final String IMAGE_READ_METHOD        = GameMemberNames.TextureLoader.READ_IMAGE;
    private static final String IMAGE_READ_DESC          = "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String IMAGEIO_OWNER            = "javax/imageio/ImageIO";
    private static final String IMAGEIO_READ_DESC        = "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    private static final String GL11_OWNER               = "org/lwjgl/opengl/GL11";
    private static final String TEXTURE_CACHE_FIELD      = GameMemberNames.TextureLoader.TEXTURE_CACHE;
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
                if (PUBLIC_LOAD_METHOD.equals(name) && PUBLIC_LOAD_DESC.equals(desc)) {
                    modified[0] = true;
                    final MethodVisitor original = super.visitMethod(
                            (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                            ORIGINAL_LOAD_METHOD,
                            desc,
                            sig,
                            ex);
                    return new PathLoadMethodReplacer(delegate, original);
                }
                if (DIMENSION_METHOD.equals(name) && DIMENSION_DESC.equals(desc)) {
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
        private final MethodVisitor original;

        PathLoadMethodReplacer(final MethodVisitor target,
                               final MethodVisitor original) {
            super(Opcodes.ASM9, original);
            this.target = target;
            this.original = original;
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
            original.visitCode();
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
            original.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
            original.visitEnd();
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
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GameClassNames.TEXTURE_OBJECT, GameMemberNames.TextureObject.SET_TEXTURE_HEIGHT, "(I)V", false);

            target.visitVarInsn(Opcodes.ALOAD, 2);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "textureWidth", "()I", false);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GameClassNames.TEXTURE_OBJECT, GameMemberNames.TextureObject.SET_TEXTURE_WIDTH, "(I)V", false);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "upperHalfColor", "()Ljava/awt/Color;", false);
            target.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, GameMemberNames.TextureLoader.UPPER_HALF_COLOR, "Ljava/awt/Color;");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "averageColor", "()Ljava/awt/Color;", false);
            target.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, GameMemberNames.TextureLoader.AVERAGE_COLOR, "Ljava/awt/Color;");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT_OWNER, "lowerHalfColor", "()Ljava/awt/Color;", false);
            target.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, GameMemberNames.TextureLoader.LOWER_HALF_COLOR, "Ljava/awt/Color;");

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