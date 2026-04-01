package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tiny v2 映射仓库的读取行为测试。
 * <p>
 * 该测试先定义映射文件加载、类名查询与缺失映射错误信息的契约，确保后续实现
 * 以 Tiny v2 作为唯一事实来源，并且对外提供可读的失败信息。
 */
class TinyV2MappingRepositoryTest {

    @Test
    void loadsTinyV2ClassMappingsFromDefaultResource() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.loadDefault();

        MappingEntry classEntry = repository.requireClassByObfuscatedName("com/fs/graphics/TextureLoader");
        assertEquals("com/fs/graphics/TextureLoader", classEntry.namedName());
        assertEquals("com/fs/graphics/TextureLoader", classEntry.obfuscatedName());
    }

    @Test
    void foreignClassMappingsMustNotPointIntoSsoptimizerPackages() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.loadDefault();

        MappingEntry classEntry = repository.requireClassByObfuscatedName("com/fs/graphics/TextureLoader");
        org.junit.jupiter.api.Assertions.assertFalse(
                classEntry.namedName().startsWith("github/kasuminova/ssoptimizer/"),
                "外部类映射不应被重写进 SSOptimizer 自己的包命名空间");
    }

            @Test
            void rejectsForeignClassMappingsIntoSsoptimizerNamespaceDuringLoad() {
            String tiny = String.join("\n",
                "tiny 2 0 obf named",
                "c com/fs/example/ExternalClass github/kasuminova/ssoptimizer/bootstrap/FakeExternal") + "\n";

            MappingLookupException exception = assertThrows(MappingLookupException.class,
                () -> TinyV2MappingRepository.loadFromResource(
                    new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)),
                    "memory:test-invalid-namespace.tiny"));

            assertTrue(exception.getMessage().contains("外部类映射不得指向 SSOptimizer 命名空间"));
            }

    @Test
    void missingClassMappingThrowsReadableError() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.loadDefault();

        MappingLookupException exception = assertThrows(MappingLookupException.class,
            () -> repository.requireClassByNamedName("com/fs/graphics/MissingClass"));
        assertEquals("未找到类映射: com/fs/graphics/MissingClass", exception.getMessage());
    }
}
