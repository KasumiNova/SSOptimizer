package github.kasuminova.ssoptimizer.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealBytecodeIntegrationTest {

    private byte[] loadClassBytes(String internalName) {
        String resource = internalName + ".class";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void combatStateSanitizedHasNoIllegalNames() {
        byte[] original = loadClassBytes("com/fs/starfarer/combat/CombatState");
        assumeTrue(original != null, "CombatState not on classpath — game JARs not available");

        var transformer = new SanitizingTransformer();
        byte[] sanitized = transformer.transform(null, "com/fs/starfarer/combat/CombatState", null, null, original);

        byte[] toCheck = sanitized != null ? sanitized : original;
        List<String> illegalNames = findIllegalNames(toCheck);
        assertTrue(illegalNames.isEmpty(),
                "Should have no illegal method/field names, but found: " + illegalNames);
    }

    @Test
    void obfJarClassesSanitizeWithoutError() {
        String[] obfClasses = {
            "com/fs/starfarer/combat/CombatState",
            "com/fs/starfarer/F",
            "com/fs/starfarer/loading/LoadingUtils",
        };
        var transformer = new SanitizingTransformer();

        for (String cls : obfClasses) {
            byte[] original = loadClassBytes(cls);
            if (original == null) continue;

            assertDoesNotThrow(
                () -> transformer.transform(null, cls, null, null, original),
                "Sanitizer should not throw for " + cls
            );
        }
    }

    @Test
    void sanitizedCombatStateIsLoadable() {
        byte[] original = loadClassBytes("com/fs/starfarer/combat/CombatState");
        assumeTrue(original != null, "CombatState not on classpath");

        var transformer = new SanitizingTransformer();
        byte[] sanitized = transformer.transform(null, "com/fs/starfarer/combat/CombatState", null, null, original);
        assumeTrue(sanitized != null, "CombatState had no illegal names to sanitize");

        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> defineIt(byte[] bytes) {
                return defineClass("com.fs.starfarer.combat.CombatState", bytes, 0, bytes.length);
            }
        };

        assertDoesNotThrow(() -> loader.defineIt(sanitized),
                "Sanitized CombatState should be loadable via defineClass");
    }

    @Test
    void sanitizedThenProcessedCombatStateIsLoadable() {
        byte[] original = loadClassBytes("com/fs/starfarer/combat/CombatState");
        assumeTrue(original != null, "CombatState not on classpath");

        var sanitizer = new SanitizingTransformer();
        byte[] sanitized = sanitizer.transform(null, "com/fs/starfarer/combat/CombatState", null, null, original);
        byte[] toProcess = sanitized != null ? sanitized : original;

        var processor = new github.kasuminova.ssoptimizer.render.engine.CombatStateProcessor();
        byte[] processed = processor.process(toProcess);

        byte[] finalBytes = processed != null ? processed : toProcess;

        List<String> illegalNames = findIllegalNames(finalBytes);
        assertTrue(illegalNames.isEmpty(),
                "Sanitized+processed CombatState should have no illegal names, found: " + illegalNames);

        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> defineIt(byte[] bytes) {
                return defineClass("com.fs.starfarer.combat.CombatState", bytes, 0, bytes.length);
            }
        };

        assertDoesNotThrow(() -> loader.defineIt(finalBytes),
                "Sanitized+processed CombatState should be loadable");
    }

    @Test
    void texturedStripRendererRewritesRealRendererBytecode() {
        byte[] original = loadClassBytes("com/fs/starfarer/renderers/o0OO");
        assumeTrue(original != null, "o0OO renderer not on classpath");

        var processor = new github.kasuminova.ssoptimizer.render.engine.EngineTexturedStripRendererProcessor();
        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Textured strip renderer processor should handle real renderer bytecode");
        assertNotNull(rewritten, "Processor should rewrite the targeted o0OO overload");

        boolean[] helperCall = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!"o00000".equals(name)
                        || !github.kasuminova.ssoptimizer.render.engine.EngineTexturedStripRendererProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(github.kasuminova.ssoptimizer.render.engine.EngineTexturedStripRendererProcessor.HELPER_OWNER)
                                && "renderTexturedStrip".equals(methodName)
                                && github.kasuminova.ssoptimizer.render.engine.EngineTexturedStripRendererProcessor.HELPER_DESC.equals(methodDesc)) {
                            helperCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(helperCall[0], "Real o0OO bytecode should call the textured strip helper after rewrite");
    }

    @Test
    void collisionGridQueryRewritesRealGridBytecode() {
        byte[] original = loadClassBytes("com/fs/starfarer/combat/o0OO/oOoO");
        assumeTrue(original != null, "Collision grid class not on classpath");

        var processor = new github.kasuminova.ssoptimizer.combat.ai.grid.CollisionGridQueryProcessor();
        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Collision grid query processor should handle real grid bytecode");
        assertNotNull(rewritten, "Processor should rewrite getCheckIterator");

        boolean[] helperCall = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!github.kasuminova.ssoptimizer.combat.ai.grid.CollisionGridQueryProcessor.TARGET_METHOD.equals(name)
                        || !github.kasuminova.ssoptimizer.combat.ai.grid.CollisionGridQueryProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(github.kasuminova.ssoptimizer.combat.ai.grid.CollisionGridQueryProcessor.HELPER_OWNER)
                                && github.kasuminova.ssoptimizer.combat.ai.grid.CollisionGridQueryProcessor.TARGET_METHOD.equals(methodName)
                                && github.kasuminova.ssoptimizer.combat.ai.grid.CollisionGridQueryProcessor.HELPER_DESC.equals(methodDesc)) {
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
        var processor = new github.kasuminova.ssoptimizer.loading.ResourceLoaderFileAccessProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.loading.ResourceLoaderFileAccessProcessor.TARGET_CLASS);
        assumeTrue(original != null, "Resource loader class not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Resource loader processor should handle real resource-loader bytecode");
        assertNotNull(rewritten, "Processor should rewrite File metadata accesses in the resource loader");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.loading.ResourceLoaderFileAccessProcessor.HELPER_OWNER,
                "exists", "(Ljava/io/File;)Z");
        assertTrue(helperCalls > 0, "Rewritten resource loader should call ResourceFileCache.exists");

        int listCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.ResourceLoaderFileAccessProcessor.HELPER_OWNER,
            "listFiles", "(Ljava/io/File;Ljava/io/FilenameFilter;)[Ljava/io/File;");
        assertTrue(listCalls > 0, "Rewritten resource loader should call ResourceFileCache.listFiles for filtered directory scans");
    }

        @Test
        void resourceLoaderRewritesRealOpenStreamBytecodeForOriginalFontOverrides() {
        var processor = new github.kasuminova.ssoptimizer.font.OriginalFontResourceStreamProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.font.OriginalFontResourceStreamProcessor.TARGET_CLASS);
        assumeTrue(original != null, "Resource loader class not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
            "Original font resource processor should handle real resource-loader bytecode");
        assertNotNull(rewritten, "Processor should rewrite managed resource openStream for original font overrides");

        int helperCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.font.OriginalFontResourceStreamProcessor.HELPER_OWNER,
            github.kasuminova.ssoptimizer.font.OriginalFontResourceStreamProcessor.HELPER_METHOD,
            github.kasuminova.ssoptimizer.font.OriginalFontResourceStreamProcessor.TARGET_DESC);
        assertTrue(helperCalls > 0,
            "Rewritten resource loader should consult OriginalGameFontOverrides.openStream before default resource lookup");
        }

    @Test
    void deferredLoaderRewritesRealParallelWorkerLifecycle() {
        var processor = new github.kasuminova.ssoptimizer.loading.ParallelImagePreloadProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.loading.ParallelImagePreloadProcessor.TARGET_CLASS);
        assumeTrue(original != null, "Deferred image loader class not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Parallel preload processor should handle real com.fs.graphics.L bytecode");
        assertNotNull(rewritten, "Processor should rewrite com.fs.graphics.L lifecycle methods");

        int startCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.loading.ParallelImagePreloadProcessor.HELPER_OWNER,
                "startWorkers", "()V");
        int stopCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.loading.ParallelImagePreloadProcessor.HELPER_OWNER,
                "stopWorkers", "()V");
        int decodeCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.ParallelImagePreloadProcessor.DECODE_HELPER_OWNER,
            "decode", github.kasuminova.ssoptimizer.loading.ParallelImagePreloadProcessor.DECODE_HELPER_DESC);
        assertTrue(startCalls > 0, "Rewritten deferred loader should call ParallelImagePreloadCoordinator.startWorkers");
        assertTrue(stopCalls > 0, "Rewritten deferred loader should call ParallelImagePreloadCoordinator.stopWorkers");
        assertTrue(decodeCalls > 0, "Rewritten deferred loader should call FastResourceImageDecoder.decode");
    }

    @Test
    void loadingUtilsRewritesRealTextReadBytecode() {
        var processor = new github.kasuminova.ssoptimizer.loading.LoadingUtilsTextProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.loading.LoadingUtilsTextProcessor.TARGET_CLASS);
        assumeTrue(original != null, "LoadingUtils not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "LoadingUtils text processor should handle real LoadingUtils bytecode");
        assertNotNull(rewritten, "Processor should rewrite LoadingUtils InputStream text reads");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.loading.LoadingUtilsTextProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.loading.LoadingUtilsTextProcessor.HELPER_METHOD,
                github.kasuminova.ssoptimizer.loading.LoadingUtilsTextProcessor.TARGET_DESC);
        assertTrue(helperCalls > 0, "Rewritten LoadingUtils should call LoadingTextResourceReader.read");
    }

    @Test
    void textureLoaderRewritesRealPixelConversionBytecode() {
        var processor = new github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.TARGET_CLASS);
        assumeTrue(original != null, "TextureLoader not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
            "TextureLoader pixel processor should handle real TextureLoader bytecode");
        assertNotNull(rewritten, "Processor should rewrite the BufferedImage pixel conversion method");

        int helperCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.HELPER_OWNER,
            "convert", "(Ljava/awt/image/BufferedImage;)Lgithub/kasuminova/ssoptimizer/loading/TexturePixelConversionResult;");
        assertTrue(helperCalls > 0, "Rewritten TextureLoader should call TexturePixelConverter.convert");

        int dimensionCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.DIMENSION_HELPER_OWNER,
            "textureDimension", "(I)I");
        assertTrue(dimensionCalls > 0, "Rewritten TextureLoader should call TextureDimensionSupport.textureDimension");

        int uploadCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.UPLOAD_HELPER_OWNER,
            "glTexImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V");
        int subUploadCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.UPLOAD_HELPER_OWNER,
            "glTexSubImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V");
        assertTrue(uploadCalls > 0 || subUploadCalls > 0,
            "Rewritten TextureLoader should route texture uploads through TextureUploadHelper");

        int decodeCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.IMAGE_READ_HELPER_OWNER,
            "decode", github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.IMAGE_READ_HELPER_DESC);
        assertTrue(decodeCalls > 0, "Rewritten TextureLoader should call FastResourceImageDecoder.decode");

        int lazyLoadCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_OWNER,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_METHOD,
            github.kasuminova.ssoptimizer.loading.TextureLoaderPixelProcessor.LAZY_LOAD_HELPER_DESC);
        assertTrue(lazyLoadCalls > 0, "Rewritten TextureLoader should call LazyTextureManager.loadTexture for path loads");
    }

    @Test
    void textureObjectRewritesRealBindBytecode() {
        var processor = new github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor.TARGET_CLASS);
        assumeTrue(original != null, "Texture object not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Texture object bind processor should handle real com.fs.graphics.Object bytecode");
        assertNotNull(rewritten, "Processor should rewrite the texture-object bind method");

        int bindCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor.HELPER_METHOD,
                github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor.HELPER_DESC);
        assertTrue(bindCalls > 0, "Rewritten texture object should route bind through LazyTextureManager.bindTexture");

        int getterCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor.HELPER_OWNER,
            github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor.HELPER_ID_METHOD,
            github.kasuminova.ssoptimizer.loading.TextureObjectBindProcessor.HELPER_ID_DESC);
        assertTrue(getterCalls > 0, "Rewritten texture object should route texture id reads through LazyTextureManager.getTextureId");
    }

        @Test
        void launcherDirectStartRewritesRealLauncherConstructorBytecode() {
        var processor = new github.kasuminova.ssoptimizer.launcher.LauncherDirectStartProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.launcher.LauncherDirectStartProcessor.TARGET_CLASS);
        assumeTrue(original != null, "StarfarerLauncher not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
            "Launcher direct-start processor should handle real StarfarerLauncher bytecode");
        assertNotNull(rewritten, "Processor should rewrite the launcher constructor");

        int helperCalls = countHelperCalls(rewritten,
            github.kasuminova.ssoptimizer.launcher.LauncherDirectStartProcessor.HELPER_OWNER,
            github.kasuminova.ssoptimizer.launcher.LauncherDirectStartProcessor.HELPER_METHOD,
            github.kasuminova.ssoptimizer.launcher.LauncherDirectStartProcessor.HELPER_DESC);
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

    private List<String> findIllegalNames(byte[] classBytes) {
        List<String> illegal = new ArrayList<>();
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (isIllegalName(name)) illegal.add("method:" + name);
                return null;
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (isIllegalName(name)) illegal.add("field:" + name);
                return null;
            }
        }, 0);
        return illegal;
    }

    private boolean isIllegalName(String name) {
        if ("<init>".equals(name) || "<clinit>".equals(name)) return false;
        for (int i = 0, len = name.length(); i < len; i++) {
            char c = name.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/') return true;
        }
        return false;
    }
}
