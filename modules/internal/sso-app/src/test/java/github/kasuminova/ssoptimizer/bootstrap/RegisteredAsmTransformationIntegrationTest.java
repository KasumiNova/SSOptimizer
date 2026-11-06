package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassReader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    private static Map<String, AsmClassProcessor> registeredProcessors() {
        Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        SSOptimizerCorePlugin.registerAllProcessors(processors::put);
        return processors;
    }

    private static byte[] loadClassBytes(final String internalName) {
        return RuntimeViewFixtures.readRuntimeNamedBytes(internalName);
    }
}
