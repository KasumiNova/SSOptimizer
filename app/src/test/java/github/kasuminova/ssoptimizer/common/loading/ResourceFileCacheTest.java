package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResourceFileCacheTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty("ssoptimizer.disable.resourcefilecache");
        ResourceFileCache.clear();
    }

    @Test
    void cachesPositiveFileProbe() throws Exception {
        Path filePath = tempDir.resolve("probe.txt");
        Files.writeString(filePath, "ok");

        File file = filePath.toFile();
        assertTrue(ResourceFileCache.exists(file));

        Files.delete(filePath);
        assertTrue(ResourceFileCache.exists(file), "Cached positive result should be reused during startup");
    }

    @Test
    void cachesDirectoryMetadata() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("nested"));
        assertTrue(ResourceFileCache.isDirectory(dir.toFile()));
    }

    @Test
    void cachesDirectoryListingAsLightweightVfsSnapshot() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("dir-snapshot"));
        Files.writeString(dir.resolve("a.txt"), "a");

        File[] first = ResourceFileCache.listFiles(dir.toFile());
        assertEquals(1, first.length);

        Files.writeString(dir.resolve("b.txt"), "b");
        File[] second = ResourceFileCache.listFiles(dir.toFile());
        assertEquals(1, second.length, "Cached snapshot should be reused during startup scans");

        ResourceFileCache.clear();
        File[] refreshed = ResourceFileCache.listFiles(dir.toFile());
        assertEquals(2, refreshed.length, "Clearing the VFS snapshot should expose new files");
    }

    @Test
    void filteredDirectoryListingUsesCachedChildren() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("dir-filter"));
        Files.writeString(dir.resolve("keep.txt"), "a");
        Files.writeString(dir.resolve("skip.png"), "b");

        FilenameFilter txtOnly = (ignored, name) -> name.endsWith(".txt");
        File[] filtered = ResourceFileCache.listFiles(dir.toFile(), txtOnly);
        assertEquals(1, filtered.length);
        assertEquals("keep.txt", filtered[0].getName());
    }

    @Test
    void infersChildExistenceFromCachedParentSnapshot() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("parent-snapshot"));
        Path child = dir.resolve("child.json");
        Files.writeString(child, "{}\n");

        ResourceFileCache.listFiles(dir.toFile());
        Files.delete(child);

        assertTrue(ResourceFileCache.exists(child.toFile()),
                "Parent directory snapshot should answer child existence without re-probing the filesystem");
    }

    @Test
    void canBeDisabledForLiveFilesystemChecks() throws Exception {
        Path filePath = tempDir.resolve("toggle.txt");
        Files.writeString(filePath, "ok");
        File file = filePath.toFile();

        assertTrue(ResourceFileCache.exists(file));
        Files.delete(filePath);

        System.setProperty("ssoptimizer.disable.resourcefilecache", "true");
        ResourceFileCache.clear();

        assertFalse(ResourceFileCache.exists(file), "Disabled cache should re-check the filesystem");
    }
}