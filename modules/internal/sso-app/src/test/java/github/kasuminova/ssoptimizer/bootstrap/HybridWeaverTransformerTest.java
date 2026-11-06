package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HybridWeaverTransformerTest {

    private static final String[] TEST_KEYS = {
            "com/example/Target", "com/example/Bad", "com/example/Foo"
    };

    @AfterEach
    void cleanupTestRegistrations() {
        for (String key : TEST_KEYS) {
            HybridWeaverTransformer.removeProcessor(key);
        }
    }

    @Test
    void returnsOriginalBytesForUnregisteredClass() {
        var transformer = new HybridWeaverTransformer();
        // RFB 的 runTransformers 会采纳 null 并丢弃类字节，未命中时必须返回原字节。
        byte[] original = {1};
        assertArrayEquals(original, transformer.transform("com.example.Unknown", "com.example.Unknown", original));
    }

    @Test
    void appliesRegisteredProcessorByTransformedName() {
        var transformer = new HybridWeaverTransformer();
        byte[] expected = {1, 2, 3};
        HybridWeaverTransformer.registerProcessor("com.example.Target", bytes -> expected);

        byte[] result = transformer.transform("com.example.Target", "com.example.Target", new byte[0]);
        assertArrayEquals(expected, result);
    }

    @Test
    void fallsBackToNameWhenTransformedNameIsNull() {
        var transformer = new HybridWeaverTransformer();
        byte[] expected = {4, 5, 6};
        HybridWeaverTransformer.registerProcessor("com/example/Target", bytes -> expected);

        assertArrayEquals(expected, transformer.transform("com/example/Target", null, new byte[0]));
    }

    @Test
    void returnsOriginalBytesWhenProcessorDoesNotModify() {
        var transformer = new HybridWeaverTransformer();
        HybridWeaverTransformer.registerProcessor("com.example.Target", bytes -> null);

        byte[] original = {1};
        assertArrayEquals(original, transformer.transform("com.example.Target", "com.example.Target", original));
    }

    @Test
    void exceptionFallsBackToOriginalBytecode() {
        var transformer = new HybridWeaverTransformer();
        HybridWeaverTransformer.registerProcessor("com.example.Bad", bytes -> {
            throw new RuntimeException("boom");
        });

        byte[] original = {1};
        assertArrayEquals(original, transformer.transform("com.example.Bad", "com.example.Bad", original));
    }

    @Test
    void registerAndRemoveProcessorChangesCount() {
        int before = HybridWeaverTransformer.getProcessorCount();
        HybridWeaverTransformer.registerProcessor("com.example.Foo", bytes -> bytes);
        assertEquals(before + 1, HybridWeaverTransformer.getProcessorCount());
        HybridWeaverTransformer.removeProcessor("com.example.Foo");
        assertEquals(before, HybridWeaverTransformer.getProcessorCount());
    }

    @Test
    void reentrantTransformPassesThroughOriginalBytes() {
        var transformer = new HybridWeaverTransformer();
        // 重入调用必须透传原字节，外层调用仍返回处理结果。
        byte[] original = {1};
        byte[] processed = {2};
        byte[][] reentrantResult = new byte[1][];
        HybridWeaverTransformer.registerProcessor("com.example.Target", bytes -> {
            reentrantResult[0] = transformer.transform("com.example.Target", "com.example.Target", bytes);
            return processed;
        });

        byte[] result = transformer.transform("com.example.Target", "com.example.Target", original);
        assertArrayEquals(original, reentrantResult[0], "重入调用应透传原字节");
        assertArrayEquals(processed, result, "外层调用应返回处理结果");

        assertArrayEquals(processed, transformer.transform("com.example.Target", "com.example.Target", original));
    }
}
