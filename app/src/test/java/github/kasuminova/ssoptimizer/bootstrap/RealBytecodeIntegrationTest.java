package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor;
import github.kasuminova.ssoptimizer.asm.render.EngineTexturedStripRendererProcessor;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealBytecodeIntegrationTest {

    /**
     * 读取运行期 named 视图字节码（ASM 处理器的工作视图），来源见 {@link RuntimeViewFixtures}。
     * <p>
     * coremod 化后该视图就是测试 classpath 上 SourceSector named jar 中的字节码本身
     * （运行期由 NanoForge 在类加载前完成 obf→named 全量 remap）。
     */
    private byte[] loadClassBytes(String internalName) {
        return RuntimeViewFixtures.readRuntimeNamedBytes(internalName);
    }

    @Test
    void texturedStripRendererRewritesRealRendererBytecode() {
        byte[] original = loadClassBytes(EngineTexturedStripRendererProcessor.TARGET_CLASS);
        assumeTrue(original != null, "TexturedStripRenderer not on classpath");

        var processor = new github.kasuminova.ssoptimizer.asm.render.EngineTexturedStripRendererProcessor();
        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Textured strip renderer processor should handle real renderer bytecode");
        assertNotNull(rewritten, "Processor should rewrite the targeted renderTexturedStrip overload");

        boolean[] helperCall = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!GameMemberNames.TexturedStripRenderer.RENDER_TEXTURED_STRIP.equals(name)
                        || !EngineTexturedStripRendererProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(EngineTexturedStripRendererProcessor.HELPER_OWNER)
                                && "renderTexturedStrip".equals(methodName)
                                && EngineTexturedStripRendererProcessor.HELPER_DESC.equals(methodDesc)) {
                            helperCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(helperCall[0], "Real TexturedStripRenderer bytecode should call the textured strip helper after rewrite");
    }

    @Test
    void collisionGridQueryRewritesRealGridBytecode() {
        byte[] original = loadClassBytes(CollisionGridQueryProcessor.TARGET_CLASS);
        assumeTrue(original != null, "CollisionGridQuery not on classpath");

        var processor = new github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor();
        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Collision grid query processor should handle real grid bytecode");
        assertNotNull(rewritten, "Processor should rewrite getCheckIterator");

        boolean[] helperCall = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor.TARGET_METHOD.equals(name)
                        || !github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor.HELPER_OWNER)
                                && github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor.TARGET_METHOD.equals(methodName)
                                && github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor.HELPER_DESC.equals(methodDesc)) {
                            helperCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(helperCall[0], "Rewritten collision grid bytecode should call CollisionGridQueryHelper.getCheckIterator");
    }

    @Test
    void resourceLoaderRewritesRealFileAccessBytecode() {
        var processor = new github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor.TARGET_CLASS);
        assumeTrue(original != null, "Resource loader class not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Resource loader processor should handle real resource-loader bytecode");
        assertNotNull(rewritten, "Processor should rewrite File metadata accesses in the resource loader");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor.HELPER_OWNER,
                "exists", "(Ljava/io/File;)Z");
        assertTrue(helperCalls > 0, "Rewritten resource loader should call ResourceFileCache.exists");

        int listCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor.HELPER_OWNER,
                "listFiles", "(Ljava/io/File;Ljava/io/FilenameFilter;)[Ljava/io/File;");
        assertTrue(listCalls > 0, "Rewritten resource loader should call ResourceFileCache.listFiles for filtered directory scans");
    }

    @Test
    void resourceLoaderRewritesRealOpenStreamBytecodeForOriginalFontOverrides() {
        var processor = new github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor.TARGET_CLASS);
        assumeTrue(original != null, "Resource loader class not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Original font resource processor should handle real resource-loader bytecode");
        assertNotNull(rewritten, "Processor should rewrite managed resource openStream for original font overrides");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor.HELPER_METHOD,
                github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor.TARGET_DESC);
        assertTrue(helperCalls > 0,
                "Rewritten resource loader should consult OriginalGameFontOverrides.openStream before default resource lookup");
    }

    @Test
    void loadingUtilsRewritesRealTextReadBytecode() {
        var processor = new github.kasuminova.ssoptimizer.asm.loading.LoadingUtilsTextProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.asm.loading.LoadingUtilsTextProcessor.TARGET_CLASS);
        assumeTrue(original != null, "LoadingUtils not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "LoadingUtils text processor should handle real LoadingUtils bytecode");
        assertNotNull(rewritten, "Processor should rewrite LoadingUtils InputStream text reads");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.LoadingUtilsTextProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.asm.loading.LoadingUtilsTextProcessor.HELPER_METHOD,
                github.kasuminova.ssoptimizer.asm.loading.LoadingUtilsTextProcessor.TARGET_DESC);
        assertTrue(helperCalls > 0, "Rewritten LoadingUtils should call LoadingTextResourceReader.read");
    }

    @Test
    void textureLoaderRewritesRealPixelConversionBytecode() {
        var processor = new github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.TARGET_CLASS);
        assumeTrue(original != null, "TextureLoader not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "TextureLoader pixel processor should handle real TextureLoader bytecode");
        assertNotNull(rewritten, "Processor should rewrite the BufferedImage pixel conversion method");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.HELPER_OWNER,
                "convert", "(Ljava/awt/image/BufferedImage;)Lgithub/kasuminova/ssoptimizer/common/loading/TexturePixelConversionResult;");
        assertTrue(helperCalls > 0, "Rewritten TextureLoader should call TexturePixelConverter.convert");

        int dimensionCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.DIMENSION_HELPER_OWNER,
                "textureDimension", "(I)I");
        assertTrue(dimensionCalls > 0, "Rewritten TextureLoader should call TextureDimensionSupport.textureDimension");

        int uploadCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.UPLOAD_HELPER_OWNER,
                "glTexImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V");
        int subUploadCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.UPLOAD_HELPER_OWNER,
                "glTexSubImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V");
        assertTrue(uploadCalls > 0 || subUploadCalls > 0,
                "Rewritten TextureLoader should route texture uploads through TextureUploadHelper");

        int decodeCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.IMAGE_READ_HELPER_OWNER,
                "decode", github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.IMAGE_READ_HELPER_DESC);
        assertTrue(decodeCalls > 0, "Rewritten TextureLoader should call FastResourceImageDecoder.decode");

        int lazyLoadCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_OWNER,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_METHOD,
                github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_DESC);
        assertTrue(lazyLoadCalls > 0, "Rewritten TextureLoader should call LazyTextureManager.loadTexture for path loads");
    }

    @Test
    void remappedTextureLoaderUsesSemanticallyCorrectImageDimensionSetters() {
        byte[] original = loadClassBytes("com/fs/graphics/TextureLoader");
        assumeTrue(original != null, "TextureLoader not on classpath");

        boolean[] widthMappedToWidthSetter = {false};
        boolean[] heightMappedToHeightSetter = {false};
        String textureObjectOwner = "com/fs/graphics/TextureObject";

        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private String lastBufferedImageDimension;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        if ("java/awt/image/BufferedImage".equals(owner)
                                && "()I".equals(methodDesc)
                                && ("getWidth".equals(methodName) || "getHeight".equals(methodName))) {
                            lastBufferedImageDimension = methodName;
                            return;
                        }

                        if (textureObjectOwner.equals(owner)
                                && "(I)V".equals(methodDesc)
                                && lastBufferedImageDimension != null) {
                            if ("getWidth".equals(lastBufferedImageDimension) && "setImageWidth".equals(methodName)) {
                                widthMappedToWidthSetter[0] = true;
                            }
                            if ("getHeight".equals(lastBufferedImageDimension) && "setImageHeight".equals(methodName)) {
                                heightMappedToHeightSetter[0] = true;
                            }
                            lastBufferedImageDimension = null;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(widthMappedToWidthSetter[0],
                "Remapped TextureLoader should route BufferedImage.getWidth() into TextureObject.setImageWidth(int)");
        assertTrue(heightMappedToHeightSetter[0],
                "Remapped TextureLoader should route BufferedImage.getHeight() into TextureObject.setImageHeight(int)");
    }

    @Test
    void textureObjectRewritesRealBindBytecode() {
        var processor = new github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor.TARGET_CLASS);
        assumeTrue(original != null, "Texture object not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Texture object bind processor should handle real com.fs.graphics.TextureObject bytecode");
        assertNotNull(rewritten, "Processor should rewrite the texture-object bind method");

        int bindCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor.HELPER_METHOD,
                github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor.HELPER_DESC);
        assertTrue(bindCalls > 0, "Rewritten texture object should route bind through LazyTextureManager.bindTexture");

        int getterCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor.HELPER_ID_METHOD,
                github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor.HELPER_ID_DESC);
        assertTrue(getterCalls > 0, "Rewritten texture object should route texture id reads through LazyTextureManager.getTextureId");
    }

    @Test
    void launcherDirectStartRewritesRealLauncherConstructorBytecode() {
        var processor = new github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.TARGET_CLASS);
        assumeTrue(original != null, "StarfarerLauncher not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Launcher direct-start processor should handle real StarfarerLauncher bytecode");
        assertNotNull(rewritten, "Processor should rewrite the launcher constructor");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.HELPER_METHOD,
                github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.HELPER_DESC);
        assertTrue(helperCalls > 0,
                "Rewritten launcher constructor should call LauncherDirectStarter.tryDirectStart");
    }

    private int countHelperCalls(byte[] classBytes, String owner, String methodName, String methodDesc) {
        int[] count = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String instructionOwner, String instructionName,
                                                String instructionDesc, boolean itf) {
                        if (owner.equals(instructionOwner)
                                && methodName.equals(instructionName)
                                && methodDesc.equals(instructionDesc)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }
}
