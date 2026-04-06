package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 平台化 mapping 资源加载测试。
 */
class PlatformMappingRepositoryTest {
    @AfterEach
    void clearMappingPlatformProperty() {
        System.clearProperty(MappingPlatform.PROPERTY);
    }

    @Test
    void defaultResourcePathFollowsConfiguredPlatform() {
        System.setProperty(MappingPlatform.PROPERTY, "linux");
        assertEquals(MappingPlatform.LINUX.resourcePath(), TinyV2MappingRepository.defaultResourcePath());

        System.setProperty(MappingPlatform.PROPERTY, "windows");
        assertEquals(MappingPlatform.WINDOWS.resourcePath(), TinyV2MappingRepository.defaultResourcePath());
    }

    @Test
    void loadsBothPlatformRepositories() {
        TinyV2MappingRepository linux = TinyV2MappingRepository.loadForPlatform(MappingPlatform.LINUX);
        TinyV2MappingRepository windows = TinyV2MappingRepository.loadForPlatform(MappingPlatform.WINDOWS);

        assertNotNull(linux.requireClassByNamedName("com/fs/graphics/TextureLoader"));
        assertNotNull(windows.requireClassByNamedName("com/fs/graphics/TextureLoader"));
        assertFalse(linux.entries().isEmpty());
        assertFalse(windows.entries().isEmpty());
    }

    @Test
    void platformMappingsKeepSameNamedSurface() {
        TinyV2MappingRepository linux = TinyV2MappingRepository.loadForPlatform(MappingPlatform.LINUX);
        TinyV2MappingRepository windows = TinyV2MappingRepository.loadForPlatform(MappingPlatform.WINDOWS);

        Set<String> linuxNamedSurface = linux.entries().stream()
                .map(entry -> entry.kind() + "|"
                        + String.valueOf(entry.ownerNamedName()) + "|"
                        + entry.namedName() + "|"
                        + String.valueOf(entry.descriptor()))
                .collect(Collectors.toSet());
        Set<String> windowsNamedSurface = windows.entries().stream()
                .map(entry -> entry.kind() + "|"
                        + String.valueOf(entry.ownerNamedName()) + "|"
                        + entry.namedName() + "|"
                        + String.valueOf(entry.descriptor()))
                .collect(Collectors.toSet());

        assertEquals(linuxNamedSurface, windowsNamedSurface,
                "Linux / Windows mapping 文件必须暴露相同的 named 语义面，避免 app 层出现平台分叉");
    }

    @Test
    void windowsRepositoryUsesKnownDivergentObfuscatedOwners() {
        TinyV2MappingRepository windows = TinyV2MappingRepository.loadForPlatform(MappingPlatform.WINDOWS);

        assertEquals("sound/C", windows.requireClassByNamedName("sound/SoundManager").obfuscatedName());
        assertEquals("com/fs/starfarer/renderers/E",
                windows.requireClassByNamedName("com/fs/starfarer/renderers/TexturedStripRenderer").obfuscatedName());
        assertEquals("com/fs/util/C", windows.requireClassByNamedName("com/fs/util/ResourceLoader").obfuscatedName());
    }

    @Test
    void resourceLoaderOpenStreamUsesPlatformSpecificObfuscatedName() {
        TinyV2MappingRepository linux = TinyV2MappingRepository.loadForPlatform(MappingPlatform.LINUX);
        TinyV2MappingRepository windows = TinyV2MappingRepository.loadForPlatform(MappingPlatform.WINDOWS);

        assertEquals("String", linux.requireMethodByNamedName(
                "com/fs/util/ResourceLoader",
                "openStream",
                "(Ljava/lang/String;)Ljava/io/InputStream;").obfuscatedName());
        assertEquals("Ô00000", windows.requireMethodByNamedName(
                "com/fs/util/ResourceLoader",
                "openStream",
                "(Ljava/lang/String;)Ljava/io/InputStream;").obfuscatedName());
        assertEquals("String", linux.requireMethodByNamedName(
                "com/fs/graphics/TextureLoader",
                "readImage",
                "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;").obfuscatedName());
        assertEquals("Ô00000", windows.requireMethodByNamedName(
                "com/fs/graphics/TextureLoader",
                "readImage",
                "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;").obfuscatedName());
    }
}
