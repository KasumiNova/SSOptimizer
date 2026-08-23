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

/**
 * GPU 显存占用探测（纯诊断，供纹理压缩/贴图管理日志输出真实显存水位）。
 * <p>
 * 三级后端，按优先级回退：
 * <ul>
 * <li>{@code GL_NVX_gpu_memory_info}（NVIDIA）：总显存 + 当前可用显存，
 * 需持 GL 上下文（RT 渲染分离模式下跳过——bridge 的 {@code glGetInteger}
 * 是全管线 drain，诊断不值得这个开销）；</li>
 * <li>{@code GL_ATI_meminfo}：纹理池可用显存（池化近似值），同上需 GL 上下文；</li>
 * <li>Linux DRM sysfs（{@code /sys/class/drm/cardN/device/mem_info_vram_*}，
 * amdgpu 等主线驱动均提供）：系统级已用/总显存，进程无关但真实可靠，
 * 无需 GL 上下文，RT 模式也可用。Mesa 不导出上面两条 GL 扩展，这是
 * Mesa 环境（AMD/Intel）的唯一通道；sysfs 可用时追加
 * {@code /proc/self/fdinfo} 的进程级显存簿记（{@code drm-memory-*}），
 * 把「系统水位」与「本进程独占占用」分开报告。</li>
 * </ul>
 */
public final class VramProbe {
    /** GL_NVX_gpu_memory_info：总可用显存（KB）。 */
    static final int GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX   = 0x9048;
    /** GL_NVX_gpu_memory_info：当前可用显存（KB）。 */
    static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    /** GL_ATI_meminfo：纹理池可用显存（KB，4 元组首值；LWJGL2 glGetInteger 取首值）。 */
    static final int GL_TEXTURE_FREE_MEMORY_ATI                      = 0x87FB;

    /** Linux DRM sysfs 根（生产固定 /sys/class/drm，测试可注入临时目录）。 */
    private static final Path DRM_SYSFS_ROOT = Path.of("/sys/class/drm");
    /** 本进程 fdinfo 目录（amdgpu 在此暴露进程级显存簿记，内核 5.19+ 完整）。 */
    private static final Path PROC_SELF_FDINFO = Path.of("/proc/self/fdinfo");

    private static final Logger LOGGER = Logger.getLogger(VramProbe.class);
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean UNSUPPORTED_LOGGED = new AtomicBoolean(false);

    /** GL 扩展探测后端。 */
    enum Backend {
        NONE, NVX, ATI
    }

    private VramProbe() {
    }

    /**
     * 扩展串解析（纯函数，单测直调）：空白分词精确匹配，NVX 优先于 ATI。
     *
     * @param extensions {@code glGetString(GL_EXTENSIONS)} 结果（可为 null）
     */
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

    /**
     * 查询当前显存水位：GL 扩展优先（非 RT 模式且持 GL 上下文时），
     * 不支持或不可用时回退 Linux sysfs；sysfs 可用时追加进程级占用（fdinfo）。
     *
     * @return 形如 {@code "vram free=5321MiB total=8192MiB (GL_NVX_gpu_memory_info)"} 或
     * {@code "vram card1: used=10189MiB total=20459MiB proc=4565MiB (sysfs+fdinfo)"} 的诊断串；
     * 全部通道不可用返回 {@code null}（记一次 INFO 说明原因）
     */
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

    /**
     * GL 扩展通道。必须在持 GL 上下文的线程上调用。
     *
     * @return 诊断串；扩展缺失返回 null（由调用方回退 sysfs），查询异常返回 null 并告警一次
     */
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
            // 典型：当前线程无 GL 上下文。告警一次，后续调用继续尝试。
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("[SSOptimizer] VRAM GL 探测失败（当前线程无 GL 上下文或驱动异常），"
                        + "回退 sysfs 通道: " + t);
            }
            return null;
        }
    }

    /**
     * Linux DRM sysfs 通道：扫描 {@code cardN/device/mem_info_vram_{total,used}}，
     * 逐卡报告（多卡机器不猜测渲染卡，全部列出）。单卡读取失败跳过该卡。
     *
     * @param drmRoot sysfs DRM 根目录（测试注入）
     * @return 诊断串；目录不存在或无有效卡返回 null
     */
    static String querySysfs(final Path drmRoot) {
        if (drmRoot == null || !Files.isDirectory(drmRoot)) {
            return null;
        }

        final List<Path> cards = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(drmRoot, "card[0-9]*")) {
            for (final Path entry : stream) {
                // 排除 card0-DP-4 之类的连接器子节点（glob 的 * 会带上它们）
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

    /** 读取单文件整数值；文件缺失/内容非法返回 null（sysfs 布局因驱动而异，属正常分支）。 */
    private static Long readLongQuietly(final Path file) {
        try {
            final String content = Files.readString(file, StandardCharsets.US_ASCII).trim();
            return Long.parseLong(content);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * 进程级显存占用（Linux fdinfo 通道）：扫描 {@code /proc/self/fdinfo} 中 amdgpu
     * 暴露的 {@code drm-pdev} / {@code drm-memory-vram} / {@code drm-memory-gtt} 簿记。
     * <p>
     * 同一进程常持有多个 DRM fd（Mesa 的 card/render 节点等），fd 间通过 dma-buf
     * 共享缓冲会被各自重复计数，故按 PCI 设备聚合时取各 fd 的<b>最大值</b>而非求和。
     *
     * @param fdinfoDir fdinfo 目录（测试注入）
     * @param drmRoot   sysfs DRM 根（用于把 PCI 地址映射回 cardN 名称）
     * @return 形如 {@code "proc=4565MiB(gtt 22MiB)@card1"} 的诊断串；无 DRM fd 返回 null
     */
    static String queryProcFdinfo(final Path fdinfoDir, final Path drmRoot) {
        if (fdinfoDir == null || !Files.isDirectory(fdinfoDir)) {
            return null;
        }

        // PCI 地址 → 各 fd 簿记（取最大，见 javadoc）
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

    /**
     * 解析单个 fdinfo 文件：含 drm-pdev 且 drm-memory-vram 非空才计入聚合。
     * 文件读取失败静默跳过（fd 随时可能被关闭，属正常竞态）。
     */
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

    /** 解析 "4674144 KiB" 形态的值为 KiB 数字；非法返回 null。 */
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

    /**
     * sysfs cardN → PCI 地址反查（cardN/device 符号链接的真实路径末段即 PCI 地址）。
     * 返回 PCI 地址 → card 名的映射；sysfs 不可用时为空映射（调用方退化为短 PCI 名）。
     */
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
                    // device 链接缺失（虚拟卡等），跳过该卡
                }
            }
        } catch (IOException e) {
            // 整体不可扫描时返回空映射，调用方退化为短 PCI 名
        }
        return names;
    }

    /** 完整 PCI 地址（0000:03:00.0）缩为短名（03:00.0），sysfs 映射缺失时的显示兜底。 */
    private static String shortPdev(final String pdev) {
        return pdev != null && pdev.length() > 5 ? pdev.substring(5) : String.valueOf(pdev);
    }

    private static long toMiB(final int kb) {
        return kb / 1024L;
    }
}
