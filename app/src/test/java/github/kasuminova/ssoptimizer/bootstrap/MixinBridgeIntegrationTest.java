package github.kasuminova.ssoptimizer.bootstrap;

import org.codehaus.janino.JavaSourceClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MixinBridgeIntegrationTest {
    private static void bootstrapMixin() {
        MixinBootstrap.init();
        Mixins.addConfiguration("mixins.ssoptimizer.json");
        advanceToDefaultPhase();
    }

    private static void advanceToDefaultPhase() {
        try {
            Class<?> phaseClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment$Phase");
            Object defaultPhase = phaseClass.getField("DEFAULT").get(null);
            Method gotoPhase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", phaseClass);
            gotoPhase.setAccessible(true);
            gotoPhase.invoke(null, defaultPhase);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to advance Mixin phase", e);
        }
    }

    private static byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream input = MixinBridgeIntegrationTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(input, "Missing class resource on test classpath: " + resourcePath);
            return input.readAllBytes();
        }
    }

    private static CompiledJaninoClass compileJaninoClass(String className, String source) throws Exception {
        Path sourceRoot = Files.createTempDirectory("ssoptimizer-janino-src");
        Path sourceFile = sourceRoot.resolve(className.replace('.', File.separatorChar) + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        try (ExposedJavaSourceClassLoader loader = new ExposedJavaSourceClassLoader(sourceRoot.toFile())) {
            Map<String, byte[]> bytecodes = loader.compile(className);
            byte[] bytes = bytecodes.get(className);
            assertNotNull(bytes, "Janino did not generate bytecode for " + className);
            return new CompiledJaninoClass(loader, bytes);
        }
    }

    private static void assertFloatConstantValue(byte[] classBytes, Object expectedValue) {
        Object[] actual = {null};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if ("COST_REDUCTION".equals(name)) {
                    actual[0] = value;
                }
                return null;
            }
        }, 0);

        assertEquals(expectedValue, actual[0], "Unexpected ConstantValue for COST_REDUCTION");
    }

    @Test
    void bridgeAppliesContrailAccessorMixinToGameClass() throws Exception {
        bootstrapMixin();

        byte[] original = readClassBytes("com/fs/starfarer/combat/entities/ContrailEngine$o.class");
        byte[] transformed = new MixinBridgeTransformer().transform(
                null,
                "com/fs/starfarer/combat/entities/ContrailEngine$o",
                null,
                null,
                original
        );

        assertNotNull(transformed);
        assertFalse(Arrays.equals(original, transformed), "ContrailEngine$o should be modified by accessor mixin");

        ClassReader reader = new ClassReader(transformed);
        assertTrue(Arrays.asList(reader.getInterfaces())
                         .contains("github/kasuminova/ssoptimizer/mixin/accessor/ContrailGroupAccessor"),
                "Transformed ContrailEngine$o should implement ContrailGroupAccessor");
    }

    @Test
    void bridgeAppliesGOverwriteMixinToGameClass() throws Exception {
        bootstrapMixin();

        byte[] original = readClassBytes("com/fs/starfarer/combat/entities/G.class");
        byte[] transformed = new MixinBridgeTransformer().transform(
                null,
                "com/fs/starfarer/combat/entities/G",
                null,
                null,
                original
        );

        assertNotNull(transformed);

        ClassReader reader = new ClassReader(transformed);
        final boolean[] foundHelper = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"o00000".equals(name) || !"(F)V".equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if ("github/kasuminova/ssoptimizer/common/render/engine/GRenderHelper".equals(owner)
                                && "renderEngines".equals(methodName)) {
                            foundHelper[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundHelper[0], "Transformed G.o00000(F)V should delegate to GRenderHelper.renderEngines");
    }

    @Test
    void bridgeSkipsJaninoLoadedClassesBeforeTouchingBytes() throws Exception {
        bootstrapMixin();

        CompiledJaninoClass compiled = compileJaninoClass(
                "thirdparty.mod.ScriptLike",
                "package thirdparty.mod; public class ScriptLike { public static final float COST_REDUCTION = 10; }"
        );

        assertFloatConstantValue(compiled.bytes(), Integer.valueOf(10));

        byte[] transformed = new MixinBridgeTransformer().transform(
                compiled.loader(),
                "thirdparty/mod/ScriptLike",
                null,
                null,
                compiled.bytes()
        );

        assertNull(transformed,
                "Mixin bridge must skip Janino-loaded third-party classes before touching their bytes");
    }

    @Test
    void bridgeSkipsUntargetedThirdPartyClasses() throws Exception {
        bootstrapMixin();

        byte[] original = readClassBytes("github/kasuminova/ssoptimizer/bootstrap/MixinBridgeTransformerTest.class");

        byte[] transformed = new MixinBridgeTransformer().transform(
                null,
                "thirdparty/mod/PlainModEntry",
                null,
                null,
                original
        );

        assertNull(transformed,
                "Untargeted Janino classes should not be rewritten by the Mixin bridge at all");
    }

    private static final class ExposedJavaSourceClassLoader extends JavaSourceClassLoader implements AutoCloseable {
        ExposedJavaSourceClassLoader(File sourceRoot) {
            super(MixinBridgeIntegrationTest.class.getClassLoader(), new File[]{sourceRoot}, null);
        }

        Map<String, byte[]> compile(String className) throws ClassNotFoundException {
            return generateBytecodes(className);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private record CompiledJaninoClass(ClassLoader loader, byte[] bytes) {
    }
}