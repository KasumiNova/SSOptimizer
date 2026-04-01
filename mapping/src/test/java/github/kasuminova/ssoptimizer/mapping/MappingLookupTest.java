package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 映射双向查询契约测试。
 * <p>
 * 该测试约束类、字段和方法都必须可以通过 obfuscated / named 两个命名空间互查，
 * 避免后续实现只支持单向转换或依赖临时字符串替换。
 */
class MappingLookupTest {

    private final MappingLookup lookup = new MappingLookup(TinyV2MappingRepository.loadDefault());

    @Test
    void classFieldAndMethodCanBeResolvedBidirectionally() {
        MappingEntry classEntry = lookup.requireClassByObfuscatedName("com/fs/graphics/TextureLoader");
        MappingEntry fieldEntry = lookup.requireFieldByNamedName("com/fs/graphics/TextureLoader", "cacheSize");
        MappingEntry methodEntry = lookup.requireMethodByObfuscatedName("com/fs/graphics/TextureLoader", "b", "(I)V");

        assertEquals("com/fs/graphics/TextureLoader", classEntry.namedName());
        assertEquals("cacheSize", fieldEntry.namedName());
        assertEquals("reloadCache", methodEntry.namedName());
        assertEquals("(I)V", methodEntry.descriptor());
    }

    @Test
    void methodCanBeResolvedByNamedDescriptorAfterClassRemap() {
        MappingEntry methodEntry = lookup.requireMethodByNamedName(
                "com/fs/graphics/TextureLoader",
                "convertPixels",
                "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/TextureObject;)Ljava/nio/ByteBuffer;");

        assertEquals("super", methodEntry.obfuscatedName());
        assertEquals("(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;", methodEntry.descriptor());
    }

    @Test
    void missingMethodMappingReportsReadableError() {
        MappingLookup lookup = new MappingLookup(TinyV2MappingRepository.loadDefault());

        MappingLookupException exception = assertThrows(MappingLookupException.class,
            () -> lookup.requireMethodByNamedName("com/fs/graphics/TextureLoader", "missingMethod", "()V"));
        assertEquals("未找到方法映射: com/fs/graphics/TextureLoader#missingMethod()V", exception.getMessage());
    }
}
