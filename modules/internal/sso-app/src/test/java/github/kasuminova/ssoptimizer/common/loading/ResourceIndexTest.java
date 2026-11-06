package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceIndexTest {
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("resource-index-test");
        Files.createDirectories(root.resolve("data/hulls/skins"));
        Files.createDirectories(root.resolve("data/variants"));
        Files.createDirectories(root.resolve("graphics/ships"));
        Files.writeString(root.resolve("data/hulls/onslaught.ship"), "{\"id\":\"onslaught\"}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/hulls/skins/onslaught_xiv.skin"), "{\"id\":\"xiv\"}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/variants/Mixed_Case.variant"), "{}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("graphics/ships/big.png"), new String(new byte[2048], StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() throws IOException {
        ResourceIndex.clear();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void lookupResolvesExactAndNormalizedPaths() {
        assertTrue(ResourceIndex.exists(root.toFile(), "data/hulls/onslaught.ship"));
        assertTrue(ResourceIndex.exists(root.toFile(), "data\\hulls\\onslaught.ship"));
        assertTrue(ResourceIndex.exists(root.toFile(), "/data/hulls/onslaught.ship"));
        assertTrue(ResourceIndex.exists(root.toFile(), "data/hulls//onslaught.ship"));
        assertTrue(ResourceIndex.exists(root.toFile(), "data/hulls/skins/../onslaught.ship"));
        assertTrue(ResourceIndex.exists(root.toFile(), "data/../data/hulls/./onslaught.ship"));
        assertNotNull(ResourceIndex.file(root.toFile(), "data/variants/../variants/Mixed_Case.variant"));
        assertFalse(ResourceIndex.exists(root.toFile(), "../data/hulls/onslaught.ship"));
        assertFalse(ResourceIndex.exists(root.toFile(), "data/hulls/missing.ship"));
    }

    @Test
    void lookupFallsBackToCaseInsensitiveMatch() {
        assertTrue(ResourceIndex.exists(root.toFile(), "data/variants/mixed_case.VARIANT"));
        assertNotNull(ResourceIndex.file(root.toFile(), "DATA/VARIANTS/Mixed_Case.variant"));
    }

    @Test
    void openServesIndexedContent() throws IOException {
        try (InputStream stream = ResourceIndex.open(root.toFile(), "data/hulls/onslaught.ship")) {
            assertNotNull(stream);
            assertEquals("{\"id\":\"onslaught\"}",
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertNull(ResourceIndex.open(root.toFile(), "data/hulls/missing.ship"));
        assertNull(ResourceIndex.open(root.toFile(), "data/hulls"));
    }

    @Test
    void childrenAreSortedAndCoverDirectEntriesOnly() {
        final List<ResourceIndex.Entry> hulls = ResourceIndex.children(root.toFile(), "data/hulls");
        assertEquals(2, hulls.size());
        assertEquals("data/hulls/onslaught.ship", hulls.get(0).relPath());
        assertEquals("data/hulls/skins", hulls.get(1).relPath());
        assertTrue(hulls.get(1).directory());

        assertEquals(1, ResourceIndex.children(root.toFile(), "data/hulls/skins").size());
        assertTrue(ResourceIndex.children(root.toFile(), "data/missing").isEmpty());

        assertEquals(2, ResourceIndex.children(root.toFile(), "data/hulls/").size());
        assertEquals(2, ResourceIndex.children(root.toFile(), "/data/hulls/").size());
    }

    @Test
    void lastModifiedMatchesFilesystem() throws IOException {
        final long expected = Files.getLastModifiedTime(root.resolve("data/hulls/onslaught.ship")).toMillis();
        assertEquals(expected, ResourceIndex.lastModified(root.toFile(), "data/hulls/onslaught.ship"));
        assertEquals(0L, ResourceIndex.lastModified(root.toFile(), "data/hulls/missing.ship"));
    }

    @Test
    void missingRootFallsBackToDirectFileAccess() {
        final File missingRoot = root.resolve("no-such-root").toFile();
        assertFalse(ResourceIndex.exists(missingRoot, "data/hulls/onslaught.ship"));
        assertTrue(ResourceIndex.children(missingRoot, "data").isEmpty());
    }

    @Test
    void prefetchServesSmallDataFilesFromMemory() throws IOException {
        assertTrue(ResourceIndex.exists(root.toFile(), "data/hulls/onslaught.ship"));
        final long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            try (InputStream stream = ResourceIndex.open(root.toFile(), "data/hulls/onslaught.ship")) {
                if (stream instanceof java.io.ByteArrayInputStream) {
                    return;
                }
            }
            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try (InputStream stream = ResourceIndex.open(root.toFile(), "data/hulls/onslaught.ship")) {
            assertEquals("{\"id\":\"onslaught\"}",
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
