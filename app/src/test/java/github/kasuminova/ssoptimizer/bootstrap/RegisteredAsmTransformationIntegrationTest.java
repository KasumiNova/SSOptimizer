package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.asm.loading.Gl11TextureTrackingProcessor;
import github.kasuminova.ssoptimizer.asm.loading.Gl12TextureTrackingProcessor;
import github.kasuminova.ssoptimizer.asm.loading.Gl30RenderbufferTrackingProcessor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 已注册 ASM 处理器的真实字节码转换测试。
 * <p>
 * 目标：覆盖 agent 运行时真正会注册的处理器集合，以及少量尚未挂到注册表、
 * 但已有真实目标类的 GL 跟踪处理器，确保“给到真实 class bytes 时至少能稳定完成一次转换”。
 */
class RegisteredAsmTransformationIntegrationTest {
    @TestFactory
    Stream<DynamicTest> registeredProcessorsTransformRealTargetClasses() throws Exception {
        final Map<String, AsmClassProcessor> processors = registeredProcessors();
        return processors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    byte[] original = loadClassBytes(entry.getKey());
                    assumeTrue(original != null, () -> "Target class not on test classpath: " + entry.getKey());

                    byte[] transformed = assertDoesNotThrow(() -> entry.getValue().process(original),
                            () -> "Processor should transform real bytecode without throwing: " + entry.getKey());
                    assertNotNull(transformed, () -> "Processor should modify its registered target class: " + entry.getKey());
                    assertDoesNotThrow(() -> new ClassReader(transformed),
                            () -> "Transformed bytecode should remain parsable: " + entry.getKey());
                }));
    }

    @TestFactory
    Stream<DynamicTest> standaloneOpenGlProcessorsTransformRuntimeClasses() {
        final Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        processors.put(Gl11TextureTrackingProcessor.TARGET_CLASS, new Gl11TextureTrackingProcessor());
        processors.put(Gl12TextureTrackingProcessor.TARGET_CLASS, new Gl12TextureTrackingProcessor());
        processors.put(Gl30RenderbufferTrackingProcessor.TARGET_CLASS, new Gl30RenderbufferTrackingProcessor());

        return processors.entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey() + "#standalone", () -> {
                    byte[] original = loadClassBytes(entry.getKey());
                    assumeTrue(original != null, () -> "OpenGL runtime class not on test classpath: " + entry.getKey());

                    byte[] transformed = assertDoesNotThrow(() -> entry.getValue().process(original),
                            () -> "Standalone OpenGL processor should not throw for " + entry.getKey());
                    assertNotNull(transformed, () -> "Standalone OpenGL processor should modify " + entry.getKey());
                    assertDoesNotThrow(() -> new ClassReader(transformed),
                            () -> "Standalone transformed bytecode should remain parsable: " + entry.getKey());
                }));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AsmClassProcessor> registeredProcessors() throws Exception {
        HybridWeaverTransformer transformer = new HybridWeaverTransformer();
        SSOptimizerAgent.registerEngineProcessors(transformer);

        Field processorsField = HybridWeaverTransformer.class.getDeclaredField("processors");
        processorsField.setAccessible(true);
        return new LinkedHashMap<>((Map<String, AsmClassProcessor>) processorsField.get(transformer));
    }

    private static byte[] loadClassBytes(final String internalName) {
        final String resourcePath = internalName + ".class";
        try (InputStream input = RegisteredAsmTransformationIntegrationTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return input != null ? input.readAllBytes() : null;
        } catch (Exception exception) {
            return null;
        }
    }
}
