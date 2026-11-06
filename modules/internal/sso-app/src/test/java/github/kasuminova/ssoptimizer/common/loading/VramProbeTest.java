package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VramProbeTest {

    @Test
    void detectsNvxBackend() {
        assertEquals(VramProbe.Backend.NVX, VramProbe.detectBackend(
                "GL_ARB_texture_compression_bptc GL_NVX_gpu_memory_info GL_EXT_texture_compression_s3tc"));
    }

    @Test
    void detectsAtiBackend() {
        assertEquals(VramProbe.Backend.ATI, VramProbe.detectBackend(
                "GL_EXT_texture_compression_s3tc GL_ATI_meminfo"));
    }

    @Test
    void nvxTakesPrecedenceOverAti() {
        assertEquals(VramProbe.Backend.NVX, VramProbe.detectBackend(
                "GL_ATI_meminfo GL_NVX_gpu_memory_info"));
    }

    @Test
    void unsupportedExtensionsYieldNone() {
        assertEquals(VramProbe.Backend.NONE, VramProbe.detectBackend(
                "GL_ARB_texture_compression_bptc GL_EXT_texture_filter_anisotropic"));
        assertEquals(VramProbe.Backend.NONE, VramProbe.detectBackend(null));
        assertEquals(VramProbe.Backend.NONE, VramProbe.detectBackend("  "));
        assertEquals(VramProbe.Backend.NONE, VramProbe.detectBackend("GL_NVX_gpu_memory_info2"));
        assertEquals(VramProbe.Backend.NONE, VramProbe.detectBackend("GL_ATI_meminfo_ext"));
    }

    @Test
    void sysfsChannelReportsEachCard(@TempDir Path tempDir) throws IOException {
        writeCard(tempDir, "card0", 2147483648L, 25235456L);
        writeCard(tempDir, "card1", 21458059264L, 10683400192L);
        Files.createDirectories(tempDir.resolve("card0-DP-4"));

        final String status = VramProbe.querySysfs(tempDir);
        assertNotNull(status);
        assertEquals("vram card0: used=24MiB total=2048MiB card1: used=10188MiB total=20464MiB (sysfs)",
                status);
    }

    @Test
    void sysfsChannelSkipsCardsWithoutMemInfo(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("card0/device"));
        assertNull(VramProbe.querySysfs(tempDir));

        writeCard(tempDir, "card1", 21458059264L, 10683400192L);
        assertEquals("vram card1: used=10188MiB total=20464MiB (sysfs)", VramProbe.querySysfs(tempDir));
    }

    @Test
    void sysfsChannelReturnsNullOutsideLinux(@TempDir Path tempDir) {
        assertNull(VramProbe.querySysfs(tempDir.resolve("nonexistent")));
    }

    @Test
    void fdinfoChannelAggregatesProcessMemoryByMaxNotSum(@TempDir Path tempDir) throws IOException {
        final Path fdinfo = Files.createDirectories(tempDir.resolve("fdinfo"));
        final String entry = """
                pos:\t0
                flags:\t02100002
                drm-driver:\tamdgpu
                drm-pdev:\t0000:03:00.0
                drm-memory-vram:\t4674144 KiB
                drm-memory-gtt: \t22544 KiB
                """;
        Files.writeString(fdinfo.resolve("52"), entry);
        Files.writeString(fdinfo.resolve("53"), entry);
        Files.writeString(fdinfo.resolve("99"), "pos:\t0\nflags:\t02100002\n");

        final Path pciDevice = Files.createDirectories(tempDir.resolve("pci/0000:03:00.0"));
        final Path card1 = Files.createDirectories(tempDir.resolve("drm/card1"));
        Files.createSymbolicLink(card1.resolve("device"), pciDevice);

        assertEquals("proc=4564MiB(gtt 22MiB)@card1",
                VramProbe.queryProcFdinfo(fdinfo, tempDir.resolve("drm")));
    }

    @Test
    void fdinfoChannelFallsBackToShortPdevWithoutSysfs(@TempDir Path tempDir) throws IOException {
        final Path fdinfo = Files.createDirectories(tempDir.resolve("fdinfo"));
        Files.writeString(fdinfo.resolve("52"), """
                drm-driver:\tamdgpu
                drm-pdev:\t0000:13:00.0
                drm-memory-vram:\t10240 KiB
                """);

        assertEquals("proc=10MiB(gtt 0MiB)@13:00.0",
                VramProbe.queryProcFdinfo(fdinfo, tempDir.resolve("nonexistent")));
        assertNull(VramProbe.queryProcFdinfo(tempDir.resolve("nonexistent"), null));
    }

    private static void writeCard(final Path root, final String name,
                                  final long totalBytes, final long usedBytes) throws IOException {
        final Path device = Files.createDirectories(root.resolve(name).resolve("device"));
        Files.writeString(device.resolve("mem_info_vram_total"), Long.toString(totalBytes));
        Files.writeString(device.resolve("mem_info_vram_used"), Long.toString(usedBytes));
    }
}
