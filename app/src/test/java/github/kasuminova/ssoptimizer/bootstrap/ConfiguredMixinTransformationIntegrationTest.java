package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.BytecodeRemapper;
import github.kasuminova.ssoptimizer.mapping.MappingDirection;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Mixin 配置全量真实转换测试。
 * <p>
 * 对 {@code mixins.ssoptimizer.json} 中声明的每个 Mixin，解析其目标类并对真实 class bytes
 * 执行一次桥接转换，确保配置面不会出现“注册了但一跑就炸”的目标类。
 */
class ConfiguredMixinTransformationIntegrationTest {
    private static final String MIXIN_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final Pattern MIXIN_ARRAY_PATTERN = Pattern.compile("\\\"mixins\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern STRING_PATTERN = Pattern.compile("\\\"([^\\\"]+)\\\"");
    private static final TinyV2MappingRepository REPOSITORY = TinyV2MappingRepository.loadDefault();
    private static final BytecodeRemapper NAMED_TO_OBFUSCATED = new BytecodeRemapper(
            REPOSITORY,
            MappingDirection.NAMED_TO_OBFUSCATED
    );

    @BeforeAll
    static void bootstrapMixin() {
        MixinBootstrap.init();
        Mixins.addConfiguration("mixins.ssoptimizer.json");
        advanceToDefaultPhase();
    }

    @TestFactory
    Stream<DynamicTest> configuredMixinsTransformRealTargets() throws Exception {
        final List<String> mixins = configuredMixins();
        return mixins.stream().map(mixinName -> DynamicTest.dynamicTest(mixinName, () -> {
            final String mixinInternalName = "github/kasuminova/ssoptimizer/mixin/" + mixinName.replace('.', '/');
            final Set<String> targets = resolveTargets(mixinInternalName);
            assertFalse(targets.isEmpty(), () -> "Mixin should expose at least one target: " + mixinName);

            for (String target : targets) {
                final byte[] original = loadClassBytes(target);
                assumeTrue(original != null, () -> "Mixin target not on test classpath: " + target + " for " + mixinName);
                final RuntimeTarget runtimeTarget = toRuntimeTarget(target, original);

                final MixinBridgeTransformer transformer = new MixinBridgeTransformer();
                final byte[] transformed = assertDoesNotThrow(
                    () -> transformer.transform(null, runtimeTarget.className(), null, null, runtimeTarget.bytecode()),
                    () -> "Mixin bridge should transform real target without throwing: " + mixinName + " -> " + runtimeTarget.className());
                assertNotNull(transformed, () -> "Mixin bridge should return transformed bytes: " + mixinName + " -> " + runtimeTarget.className());
                assertDoesNotThrow(() -> new ClassReader(transformed),
                    () -> "Mixin-transformed bytes should remain parsable: " + mixinName + " -> " + runtimeTarget.className());
            }
        }));
    }

            private static RuntimeTarget toRuntimeTarget(final String namedTarget,
                                 final byte[] namedBytecode) {
            final MappingEntry classEntry = REPOSITORY.findClassByNamedName(namedTarget).orElse(null);
            if (classEntry == null || classEntry.obfuscatedName().equals(namedTarget)) {
                return new RuntimeTarget(namedTarget, namedBytecode);
            }

            final BytecodeRemapper.RemappedClass remapped = NAMED_TO_OBFUSCATED.remapClass(namedBytecode);
            return new RuntimeTarget(classEntry.obfuscatedName(), remapped.bytecode());
            }

    private static List<String> configuredMixins() throws Exception {
        try (InputStream input = ConfiguredMixinTransformationIntegrationTest.class.getClassLoader()
                .getResourceAsStream("mixins.ssoptimizer.json")) {
            assertNotNull(input, "mixins.ssoptimizer.json 应出现在测试 classpath 上");
            final String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            final Matcher blockMatcher = MIXIN_ARRAY_PATTERN.matcher(content);
            assertTrue(blockMatcher.find(), "mixins 数组应出现在 mixins.ssoptimizer.json 中");
            final Matcher entryMatcher = STRING_PATTERN.matcher(blockMatcher.group(1));
            final java.util.ArrayList<String> result = new java.util.ArrayList<>();
            while (entryMatcher.find()) {
                result.add(entryMatcher.group(1));
            }
            return result;
        }
    }

    private static Set<String> resolveTargets(final String mixinInternalName) {
        final Set<String> targets = new LinkedHashSet<>();
        final byte[] mixinBytes = loadClassBytes(mixinInternalName);
        assertNotNull(mixinBytes, () -> "Missing mixin bytecode on test classpath: " + mixinInternalName);

        new ClassReader(mixinBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(final String descriptor,
                                                     final boolean visible) {
                if (!MIXIN_ANNOTATION_DESC.equals(descriptor)) {
                    return super.visitAnnotation(descriptor, visible);
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(final String name) {
                        if ("targets".equals(name)) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(final String entryName,
                                                  final Object value) {
                                    if (value instanceof String target) {
                                        targets.add(target.replace('.', '/'));
                                    }
                                }
                            };
                        }
                        if ("value".equals(name)) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(final String entryName,
                                                  final Object value) {
                                    if (value instanceof Type targetType) {
                                        targets.add(targetType.getInternalName());
                                    }
                                }
                            };
                        }
                        return super.visitArray(name);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return targets;
    }

    private static byte[] loadClassBytes(final String internalName) {
        try (InputStream input = ConfiguredMixinTransformationIntegrationTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            return input != null ? input.readAllBytes() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static void advanceToDefaultPhase() {
        try {
            final Class<?> phaseClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment$Phase");
            final Object defaultPhase = phaseClass.getField("DEFAULT").get(null);
            final Method gotoPhase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", phaseClass);
            gotoPhase.setAccessible(true);
            gotoPhase.invoke(null, defaultPhase);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to advance Mixin phase", exception);
        }
    }

    private record RuntimeTarget(String className,
                                 byte[] bytecode) {
    }
}
