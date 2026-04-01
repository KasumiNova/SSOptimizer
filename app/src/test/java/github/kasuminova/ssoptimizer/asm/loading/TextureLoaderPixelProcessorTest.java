package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class TextureLoaderPixelProcessorTest {
    @Test
    void rewritesBufferedImageConversionMethodToHelper() {
        byte[] rewritten = new TextureLoaderPixelProcessor().process(createFakeTextureLoaderClass());
        assertNotNull(rewritten);

        Inspection inspection = inspect(rewritten);
        assertTrue(inspection.callsHelper, "TextureLoader conversion should delegate to TexturePixelConverter");
        assertTrue(inspection.callsDimensionHelper, "TextureLoader dimension helper should delegate to TextureDimensionSupport");
        assertTrue(inspection.callsTextureSizeSetters, "TextureLoader conversion should still update texture dimensions");
        assertTrue(inspection.callsImageReadHelper, "TextureLoader image read should delegate to FastResourceImageDecoder");
        assertTrue(inspection.callsUploadHelper, "TextureLoader uploads should delegate to TextureUploadHelper");
        assertTrue(inspection.callsLazyLoadHelper, "TextureLoader path load should delegate to LazyTextureManager");
        assertFalse(inspection.callsRasterGetPixel, "Raster.getPixel should disappear after rewrite");
        assertFalse(inspection.callsImageIoRead, "ImageIO.read should disappear after rewrite");
        assertFalse(inspection.callsRawTexImage2D, "Raw GL11.glTexImage2D should disappear after rewrite");
    }

    private byte[] createFakeTextureLoaderClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TextureLoaderPixelProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE, GameMemberNames.TextureLoader.UPPER_HALF_COLOR, "Ljava/awt/Color;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, GameMemberNames.TextureLoader.AVERAGE_COLOR, "Ljava/awt/Color;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, GameMemberNames.TextureLoader.LOWER_HALF_COLOR, "Ljava/awt/Color;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, GameMemberNames.TextureLoader.TEXTURE_CACHE, "Ljava/util/HashMap;", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, TextureLoaderPixelProcessor.TARGET_METHOD,
                TextureLoaderPixelProcessor.TARGET_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/awt/image/BufferedImage", "getData", "()Ljava/awt/image/Raster;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 3);
        mv.visitEnd();

        MethodVisitor dimension = cw.visitMethod(Opcodes.ACC_PRIVATE, TextureLoaderPixelProcessor.DIMENSION_METHOD,
                "(I)I", null, null);
        dimension.visitCode();
        dimension.visitInsn(Opcodes.ICONST_2);
        dimension.visitInsn(Opcodes.IRETURN);
        dimension.visitMaxs(0, 2);
        dimension.visitEnd();

        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PRIVATE, "String",
                "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;", null, null);
        read.visitCode();
        read.visitInsn(Opcodes.ACONST_NULL);
        read.visitMethodInsn(Opcodes.INVOKESTATIC, "javax/imageio/ImageIO", "read", "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;", false);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 2);
        read.visitEnd();

        MethodVisitor upload = cw.visitMethod(Opcodes.ACC_PUBLIC, "upload",
                "()V", null, null);
        upload.visitCode();
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitInsn(Opcodes.ACONST_NULL);
        upload.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glTexImage2D",
                "(IIIIIIIILjava/nio/ByteBuffer;)V", false);
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(0, 1);
        upload.visitEnd();

        MethodVisitor loadPath = cw.visitMethod(Opcodes.ACC_PUBLIC, TextureLoaderPixelProcessor.PUBLIC_LOAD_METHOD,
                TextureLoaderPixelProcessor.PUBLIC_LOAD_DESC, null, new String[]{"java/io/IOException"});
        loadPath.visitCode();
        loadPath.visitInsn(Opcodes.ACONST_NULL);
        loadPath.visitInsn(Opcodes.ARETURN);
        loadPath.visitMaxs(0, 2);
        loadPath.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private Inspection inspect(byte[] classBytes) {
        boolean[] helper = {false};
        boolean[] dimensionHelper = {false};
        boolean[] textureSizeSetters = {false};
        boolean[] rasterGetPixel = {false};
        boolean[] imageReadHelper = {false};
        boolean[] imageIoRead = {false};
        boolean[] uploadHelper = {false};
        boolean[] rawTexImage2D = {false};
        boolean[] lazyLoadHelper = {false};

        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (TextureLoaderPixelProcessor.TARGET_METHOD.equals(name)
                                && TextureLoaderPixelProcessor.TARGET_DESC.equals(desc)
                                && TextureLoaderPixelProcessor.HELPER_OWNER.equals(owner)
                                && "convert".equals(methodName)) {
                            helper[0] = true;
                        }
                        if (TextureLoaderPixelProcessor.DIMENSION_METHOD.equals(name)
                            && "(I)I".equals(desc)
                                && TextureLoaderPixelProcessor.DIMENSION_HELPER_OWNER.equals(owner)
                                && "textureDimension".equals(methodName)) {
                            dimensionHelper[0] = true;
                        }
                        if (TextureLoaderPixelProcessor.TARGET_METHOD.equals(name)
                                && TextureLoaderPixelProcessor.TARGET_DESC.equals(desc)
                                && GameClassNames.TEXTURE_OBJECT.equals(owner)
                                && (GameMemberNames.TextureObject.SET_TEXTURE_HEIGHT.equals(methodName)
                                    || GameMemberNames.TextureObject.SET_TEXTURE_WIDTH.equals(methodName))) {
                            textureSizeSetters[0] = true;
                        }
                        if (TextureLoaderPixelProcessor.TARGET_METHOD.equals(name)
                                && TextureLoaderPixelProcessor.TARGET_DESC.equals(desc)
                                && "java/awt/image/Raster".equals(owner) && "getPixel".equals(methodName)) {
                            rasterGetPixel[0] = true;
                        }
                        if ("String".equals(name)
                                && "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;".equals(desc)
                                && TextureLoaderPixelProcessor.IMAGE_READ_HELPER_OWNER.equals(owner)
                                && "decode".equals(methodName)) {
                            imageReadHelper[0] = true;
                        }
                        if (TextureLoaderPixelProcessor.UPLOAD_HELPER_OWNER.equals(owner)
                                && "glTexImage2D".equals(methodName)) {
                            uploadHelper[0] = true;
                        }
                        if (TextureLoaderPixelProcessor.PUBLIC_LOAD_METHOD.equals(name)
                                && TextureLoaderPixelProcessor.PUBLIC_LOAD_DESC.equals(desc)
                                && TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_OWNER.equals(owner)
                                && TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_METHOD.equals(methodName)) {
                            lazyLoadHelper[0] = true;
                        }
                        if ("javax/imageio/ImageIO".equals(owner) && "read".equals(methodName)) {
                            imageIoRead[0] = true;
                        }
                        if ("org/lwjgl/opengl/GL11".equals(owner) && "glTexImage2D".equals(methodName)) {
                            rawTexImage2D[0] = true;
                        }
                    }
                };
            }
        }, 0);

        return new Inspection(helper[0], dimensionHelper[0], textureSizeSetters[0], rasterGetPixel[0], imageReadHelper[0], imageIoRead[0], uploadHelper[0], rawTexImage2D[0], lazyLoadHelper[0]);
    }

    private record Inspection(boolean callsHelper,
                              boolean callsDimensionHelper,
                              boolean callsTextureSizeSetters,
                              boolean callsRasterGetPixel,
                              boolean callsImageReadHelper,
                              boolean callsImageIoRead,
                              boolean callsUploadHelper,
                              boolean callsRawTexImage2D,
                              boolean callsLazyLoadHelper) {
    }
}