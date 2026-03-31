package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsmCommonSuperClassResolverTest {
    @Test
    void resolvesJdkStreamHierarchyPrecisely() {
        assertEquals("java/io/InputStream",
                AsmCommonSuperClassResolver.resolve("java/io/BufferedInputStream", "java/io/InputStream"));
    }

    @Test
    void fallsBackToObjectForUnknownGameTypes() {
        assertEquals("java/lang/Object",
                AsmCommonSuperClassResolver.resolve("com/fs/graphics/TextureLoader", "com/fs/graphics/Object"));
    }
}