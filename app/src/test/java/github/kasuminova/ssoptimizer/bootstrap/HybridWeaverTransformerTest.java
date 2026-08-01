package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HybridWeaverTransformerTest {

    private static final String[] TEST_KEYS = {
            "com/example/Target", "com/example/Bad", "com/example/Foo"
    };

    /**
     * 注册表为静态共享状态，每个用例结束后清理本类注册的测试键，避免用例间污染。
     */
    @AfterEach
    void cleanupTestRegistrations() {
        for (String key : TEST_KEYS) {
            HybridWeaverTransformer.removeProcessor(key);
        }
    }

    @Test
    void returnsOriginalBytesForUnregisteredClass() {
        var transformer = new HybridWeaverTransformer();
        // RFB 契约：runTransformers 无条件采纳返回值，null 会丢弃类字节，
        // 未命中必须返回原字节（区别于原版 LaunchWrapper 的 null = 无变更）
        byte[] original = {1};
        assertArrayEquals(original, transformer.transform("com.example.Unknown", "com.example.Unknown", original));
    }

    @Test
    void appliesRegisteredProcessorByTransformedName() {
        var transformer = new HybridWeaverTransformer();
        byte[] expected = {1, 2, 3};
        HybridWeaverTransformer.registerProcessor("com.example.Target", bytes -> expected);

        // transformedName 为点号格式（LaunchWrapper 语义），注册表统一按斜杠格式匹配
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
        // 模拟「处理器执行期间同类字节经 Mixin 反读再次进入 transform」的重入场景：
        // 重入调用必须透传原字节，外层调用仍返回处理结果
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

        // 重入状态必须在 finally 中清理，后续独立调用不受影响
        assertArrayEquals(processed, transformer.transform("com.example.Target", "com.example.Target", original));
    }
}
