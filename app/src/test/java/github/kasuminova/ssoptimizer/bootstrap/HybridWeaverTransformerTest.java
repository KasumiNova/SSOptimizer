package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HybridWeaverTransformerTest {
    @Test
    void returnsNullForUnregisteredClass() {
        var transformer = new HybridWeaverTransformer();
        assertNull(transformer.transform(null, "com/example/Unknown", null, null, new byte[]{1}));
    }

    @Test
    void appliesRegisteredProcessor() {
        var transformer = new HybridWeaverTransformer();
        byte[] expected = {1, 2, 3};
        transformer.registerProcessor("com.example.Target", bytes -> expected);

        byte[] result = transformer.transform(null, "com/example/Target", null, null, new byte[0]);
        assertArrayEquals(expected, result);
    }

    @Test
    void exceptionFallsBackToOriginalBytecode() {
        var transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("com.example.Bad", bytes -> {
            throw new RuntimeException("boom");
        });

        assertNull(transformer.transform(null, "com/example/Bad", null, null, new byte[]{1}));
    }

    @Test
    void registerAndRemoveProcessorChangesCount() {
        var transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("com.example.Foo", bytes -> bytes);
        assertEquals(1, transformer.getProcessorCount());
        transformer.removeProcessor("com.example.Foo");
        assertEquals(0, transformer.getProcessorCount());
    }

    @Test
    void resolvesObfuscatedIncomingClassNameThroughMappings() {
        var transformer = new HybridWeaverTransformer(TinyV2MappingRepository.of(java.util.List.of(
                MappingEntry.classEntry("com/fs/starfarer/renderers/o0OO", "com/fs/starfarer/renderers/TexturedStripRenderer")
        )));
        byte[] expected = {4, 5, 6};
        transformer.registerProcessor("com.fs.starfarer.renderers.TexturedStripRenderer", bytes -> expected);

        byte[] result = transformer.transform(null, "com/fs/starfarer/renderers/o0OO", null, null, new byte[0]);
        assertArrayEquals(expected, result);
    }
}
