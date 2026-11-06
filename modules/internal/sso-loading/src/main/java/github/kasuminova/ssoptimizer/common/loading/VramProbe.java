package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VramProbe {
    static final int GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX   = 0x9048;
    static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    static final int GL_TEXTURE_FREE_MEMORY_ATI                      = 0x87FB;

    private static final Path DRM_SYSFS_ROOT = Path.of("/sys/class/drm");
    private static final Path PROC_SELF_FDINFO = Path.of("/proc/self/fdinfo");

    private static final Logger LOGGER = Logger.getLogger(VramProbe.class);
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean UNSUPPORTED_LOGGED = new AtomicBoolean(false);

    enum Backend {
        NONE, NVX, ATI
    }

    private VramProbe() {
    }

    static Backend detectBackend(final String extensions) {
        if (extensions == null || extensions.isBlank()) {
            return Backend.NONE;
        }
        boolean nvx = false;
        boolean ati = false;
        for (final String token : extensions.trim().split("\\s+")) {
            if (token.equals("GL_NVX_gpu_memory_info")) {
                nvx = true;
            } else if (token.equals("GL_ATI_meminfo")) {
                ati = true;
            }
        }
        if (nvx) {
            return Backend.NVX;
        }
        return ati ? Backend.ATI : Backend.NONE;
    }

    static String queryStatus() {
        if (!RenderThreadMode.isEnabled()) {
            final String glStatus = queryViaGl();
            if (glStatus != null) {
                return glStatus;
            }
        }
        final String sysfsStatus = querySysfs(DRM_SYSFS_ROOT);
        final String procStatus = queryProcFdinfo(PROC_SELF_FDINFO, DRM_SYSFS_ROOT);
        if (sysfsStatus != null) {
            return procStatus == null ? sysfsStatus
                    : sysfsStatus.substring(0, sysfsStatus.length() - " (sysfs)".length())
                            + ' ' + procStatus + " (sysfs+fdinfo)";
        }
        if (procStatus != null) {
            return "vram " + procStatus + " (fdinfo)";
        }
        if (UNSUPPORTED_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[SSOptimizer] VRAM 探测不可用：驱动不支持 GL_NVX_gpu_memory_info / "
                    + "GL_ATI_meminfo，且非 Linux DRM sysfs 环境");
        }
        return null;
    }

    private static String queryViaGl() {
        try {
            final Backend backend = detectBackend(GL11.glGetString(GL11.GL_EXTENSIONS));
            switch (backend) {
                case NVX -> {
                    final int totalKb = GL11.glGetInteger(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX);
                    final int freeKb = GL11.glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
                    return "vram free=" + toMiB(freeKb) + "MiB total=" + toMiB(totalKb)
                            + "MiB (GL_NVX_gpu_memory_info)";
                }
                case ATI -> {
                    final int freeKb = GL11.glGetInteger(GL_TEXTURE_FREE_MEMORY_ATI);
                    return "vram free~" + toMiB(freeKb) + "MiB (GL_ATI_meminfo, 池化近似)";
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("[SSOptimizer] VRAM GL 探测失败（当前线程无 GL 上下文或驱动异常），"
                        + "回退 sysfs 通道: " + t);
            }
            return null;
        }
    }

    static String querySysfs(final Path drmRoot) {
        if (drmRoot == null || !Files.isDirectory(drmRoot)) {
            return null;
        }

        final List<Path> cards = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(drmRoot, "card[0-9]*")) {
            for (final Path entry : stream) {
                if (!entry.getFileName().toString().contains("-")) {
                    cards.add(entry);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] VRAM sysfs 扫描失败（" + drmRoot + "）: " + e);
            return null;
        }
        cards.sort(Comparator.comparing(p -> p.getFileName().toString()));

        final StringBuilder sb = new StringBuilder("vram");
        int found = 0;
        for (final Path card : cards) {
            final Path device = card.resolve("device");
            final Long total = readLongQuietly(device.resolve("mem_info_vram_total"));
            final Long used = readLongQuietly(device.resolve("mem_info_vram_used"));
            if (total == null || used == null) {
                continue;
            }
            sb.append(' ').append(card.getFileName()).append(": used=").append(used / (1024L * 1024L))
                    .append("MiB total=").append(total / (1024L * 1024L)).append("MiB");
            found++;
        }
        return found > 0 ? sb.append(" (sysfs)").toString() : null;
    }

    private static Long readLongQuietly(final Path file) {
        try {
            final String content = Files.readString(file, StandardCharsets.US_ASCII).trim();
            return Long.parseLong(content);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    // 同一 DRM 缓冲可能经多个 fd 重复计数，因此按 PCI 设备聚合时取各 fd 最大值。
    static String queryProcFdinfo(final Path fdinfoDir, final Path drmRoot) {
        if (fdinfoDir == null || !Files.isDirectory(fdinfoDir)) {
            return null;
        }

        final Map<String, long[]> byPdev = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fdinfoDir)) {
            for (final Path entry : stream) {
                parseFdinfoEntry(entry, byPdev);
            }
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] VRAM fdinfo 扫描失败（" + fdinfoDir + "）: " + e);
            return null;
        }
        if (byPdev.isEmpty()) {
            return null;
        }

        final Map<String, String> cardNames = resolveCardNames(drmRoot);
        final List<String> segments = new ArrayList<>(byPdev.size());
        byPdev.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    final long[] vramGtt = e.getValue();
                    final String card = cardNames.getOrDefault(e.getKey(), shortPdev(e.getKey()));
                    segments.add("proc=" + (vramGtt[0] / 1024L) + "MiB(gtt "
                            + (vramGtt[1] / 1024L) + "MiB)@" + card);
                });
        return String.join(" ", segments);
    }

    private static void parseFdinfoEntry(final Path entry, final Map<String, long[]> byPdev) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(entry, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            return;
        }

        String pdev = null;
        Long vramKb = null;
        Long gttKb = null;
        for (final String line : lines) {
            if (line.startsWith("drm-pdev:")) {
                pdev = line.substring("drm-pdev:".length()).trim();
            } else if (line.startsWith("drm-memory-vram:")) {
                vramKb = parseKibValue(line.substring("drm-memory-vram:".length()));
            } else if (line.startsWith("drm-memory-gtt:")) {
                gttKb = parseKibValue(line.substring("drm-memory-gtt:".length()));
            }
        }
        if (pdev == null || vramKb == null) {
            return;
        }

        final long[] aggregate = byPdev.computeIfAbsent(pdev, k -> new long[2]);
        aggregate[0] = Math.max(aggregate[0], vramKb);
        aggregate[1] = Math.max(aggregate[1], gttKb == null ? 0L : gttKb);
    }

    private static Long parseKibValue(final String raw) {
        final String trimmed = raw.trim();
        final int space = trimmed.indexOf(' ');
        final String number = space > 0 ? trimmed.substring(0, space) : trimmed;
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> resolveCardNames(final Path drmRoot) {
        final Map<String, String> names = new HashMap<>();
        if (drmRoot == null || !Files.isDirectory(drmRoot)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(drmRoot, "card[0-9]*")) {
            for (final Path card : stream) {
                final String cardName = card.getFileName().toString();
                if (cardName.contains("-")) {
                    continue;
                }
                try {
                    final Path real = card.resolve("device").toRealPath();
                    names.put(real.getFileName().toString(), cardName);
                } catch (IOException e) {
                    // 虚拟卡等设备可能没有该链接。
                }
            }
        } catch (IOException e) {
            // sysfs 不可扫描时使用短 PCI 名。
        }
        return names;
    }

    private static String shortPdev(final String pdev) {
        return pdev != null && pdev.length() > 5 ? pdev.substring(5) : String.valueOf(pdev);
    }

    private static long toMiB(final int kb) {
        return kb / 1024L;
    }
}
