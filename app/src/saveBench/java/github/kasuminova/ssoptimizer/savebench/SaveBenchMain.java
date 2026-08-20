package github.kasuminova.ssoptimizer.savebench;

import com.fs.starfarer.api.ModPlugin;
import com.fs.starfarer.api.impl.campaign.procgen.MarkovNames;
import com.fs.starfarer.campaign.save.CampaignGameManager;
import com.fs.starfarer.campaign.save.SaveGameData;
import com.fs.starfarer.loading.specs.CustomEntitySpec;
import com.fs.starfarer.loading.ResourceLoaderState;
import com.fs.starfarer.loading.ShipNameStore;
import com.fs.starfarer.loading.SpecStore;
import com.fs.starfarer.loading.StarfarerStrings;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.reflection.ReflectionConverter;
import github.kasuminova.ssoptimizer.common.bench.BenchmarkProfiler;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/**
 * 离线存档序列化基准入口。
 *
 * <p>在普通 JVM 中复现游戏 {@code CampaignGameManager.getXStream} 的完整配置
 * （别名表 + converter + ID_REFERENCES + 模组 {@code configureXStream}），
 * 对指定真实存档执行 fromXML / toXML / roundtrip，输出分阶段耗时与
 * async-profiler 热点，作为序列化优化的基线与回归工具。</p>
 *
 * <p>注意：离线环境不应用 SSOptimizer 的任何 Mixin/ASM 改写，测得的是
 * <b>原版 XStream 内核</b>的基线；优化后的实机回归走 save_load_smoke 链路。</p>
 *
 * <p>roundtrip 模式的正确性判据：同一对象图两次 marshal 的字节必须完全一致
 * （确定性输出 = 语义保持），以及 marshal→unmarshal→marshal 的二次输出与一次输出
 * 字节一致（往返语义保持）。</p>
 */
public final class SaveBenchMain {
    private static final Logger LOGGER = Logger.getLogger(SaveBenchMain.class);

    private static final String MODE_LOAD = "load";
    private static final String MODE_SAVE = "save";
    private static final String MODE_ROUNDTRIP = "roundtrip";
    private static final String MODE_DIAG_SPECS = "diag-specs";

    private SaveBenchMain() {
    }

    public static void main(final String[] args) throws Exception {
        final Path gameDir = Path.of(System.getProperty("sso.savebench.gameDir"));
        final Path saveDir = resolveSaveDir(gameDir, System.getProperty("sso.savebench.saveDir"));
        final String mode = System.getProperty("sso.savebench.mode", MODE_ROUNDTRIP);
        final boolean profile = Boolean.parseBoolean(System.getProperty("sso.savebench.profile", "true"));
        final Path outputDir = Path.of(System.getProperty("sso.savebench.outputDir"));
        Files.createDirectories(outputDir);

        LOGGER.info("[SaveBench] gameDir=" + gameDir + " saveDir=" + saveDir + " mode=" + mode);

        // ---- 环境引导（顺序敏感，复刻 ResourceLoaderState 启动序列）----
        final List<? extends com.fs.starfarer.api.ModSpecAPI> enabledMods = GameEnvBootstrap.setup(gameDir);
        ScriptClassLoaderSetup.begin(gameDir, enabledMods);
        final List<ModPlugin> plugins = ModPluginBootstrap.setup();

        // ---- 静态数据层（SpecStore）----
        // 与游戏启动序列一致（ResourceLoaderState）：先 loadStarmap 再 onApplicationLoad。
        // 传入哑 ResourceLoaderState（默认构造、不调 init()）：loadMissions 等方法会
        // 无条件调用 queueResource 收集预载队列，null 会 NPE；queueResource 本身只是
        // 向列表追加，无 GL 触碰，队列内容离线直接丢弃。
        // 离线 unmarshal 的硬依赖：Faction.<clinit>（NO_FACTION）等会在类初始化时
        // 访问 SpecStore，数据未加载即 NPE。
        // 字符串/名称表：游戏启动序列（ResourceLoaderState）在 loadStarmap 前加载，
        // 模组插件 onApplicationLoad 普遍依赖 StarfarerStrings（DMSUtil 等静态初始化即访问）。
        ShipNameStore.loadNames();
        StarfarerStrings.load();
        long start = System.nanoTime();
        SpecStore.loadStarmap(new ResourceLoaderState());
        LOGGER.info(String.format(Locale.ROOT, "[SaveBench] spec store loaded: %.0f ms", elapsedMs(start)));
        MarkovNames.loadIfNeeded();
        // 等待脚本加载线程（游戏顺序：资源加载后、onApplicationLoad 前 waitForLoading）
        ScriptClassLoaderSetup.finish();
        // onApplicationLoad 必须执行：部分模组在其中向 SpecStore 注册运行期内容
        // （如 custom entity spec），缺了会导致读档 readResolve 时 spec 缺失 NPE。
        // 显式环境差异适配（非兜底）：模组插件在 onApplicationLoad 初始化 GL shader
        // （GraphicsLib ShaderLib.init 等）在无 GL 上下文的离线 JVM 中必炸，
        // 仅当根因确为「无 GL 上下文」时跳过该插件并记录；其余异常原样抛出。
        // 显式环境差异适配（非兜底），两类已知差异分别记录：
        // 1. 无 GL 上下文：模组插件在 onApplicationLoad 初始化 GL shader/纹理
        //    （GraphicsLib ShaderLib.init、CombatEngine 单例触碰等），离线必炸；
        // 2. 模组字节码未 remap：模组直接引用游戏混淆名类（如 loading.specs.g），
        //    游戏运行期由 NanoForge remap 改写符号引用，离线 classpath 无 remap 层，
        //    类解析失败。configureXStream 为独立方法仍会被调用，XStream 语义不受影响。
        int glSkipped = 0;
        int remapSkipped = 0;
        for (final ModPlugin plugin : plugins) {
            try {
                plugin.onApplicationLoad();
            } catch (final Exception | LinkageError e) {
                if (isMissingGlContext(e)) {
                    glSkipped++;
                    LOGGER.warn("[SaveBench] onApplicationLoad 跳过（无 GL 上下文）: "
                            + plugin.getClass().getName());
                } else if (e instanceof NoClassDefFoundError) {
                    remapSkipped++;
                    LOGGER.warn("[SaveBench] onApplicationLoad 跳过（模组字节码未 remap，缺类 "
                            + e.getMessage() + "）: " + plugin.getClass().getName());
                } else if (e instanceof LinkageError error) {
                    throw error;
                } else {
                    throw (Exception) e;
                }
            }
        }
        LOGGER.info("[SaveBench] onApplicationLoad done, gl-skipped=" + glSkipped
                + " remap-skipped=" + remapSkipped);

        if (MODE_DIAG_SPECS.equals(mode)) {
            // 诊断模式：dump 全部 CustomEntitySpec id 到 outputDir/custom-entity-spec-ids.txt
            final Collection<String> ids = SpecStore.getSpecIds(CustomEntitySpec.class);
            final Path out = outputDir.resolve("custom-entity-spec-ids.txt");
            Files.writeString(out, ids.stream().sorted().collect(java.util.stream.Collectors.joining("\n")) + "\n");
            LOGGER.info("[SaveBench] custom entity spec ids dumped: " + ids.size() + " -> " + out);
            return;
        }

        final BenchmarkProfiler profiler = profile ? BenchmarkProfiler.create(outputDir) : null;
        if (profiler != null) {
            profiler.start(System.getProperty("sso.savebench.profile.event", "cpu"));
        }
        try {
            final XStream xstream = CampaignGameManager.getXStream("0.6");
            shadowCampaignEngineConverter(xstream);
            final Summary summary = new Summary();
            summary.saveDir = saveDir.toString();
            summary.mode = mode;

            // ---- descriptor ----
            start = System.nanoTime();
            final SaveGameData descriptor = readDescriptor(xstream, saveDir);
            summary.descriptorMs = elapsedMs(start);
            summary.characterName = descriptor.getCharacterName();
            summary.saveFileVersion = descriptor.getSaveFileVersion();
            LOGGER.info("[SaveBench] descriptor: " + descriptor.getCharacterName()
                    + " version=" + descriptor.getSaveFileVersion()
                    + " mods=" + descriptor.getEnabledMods().size());

            // ---- campaign load ----
            start = System.nanoTime();
            final Object campaign = readCampaign(xstream, saveDir);
            summary.loadMs = elapsedMs(start);
            summary.payloadBytes = payloadSize(saveDir);
            LOGGER.info(String.format(Locale.ROOT, "[SaveBench] load: %.0f ms (%.1f MB)",
                    summary.loadMs, summary.payloadBytes / 1e6));

            if (MODE_SAVE.equals(mode) || MODE_ROUNDTRIP.equals(mode)) {
                // ---- marshal #1 ----
                start = System.nanoTime();
                final byte[] first = marshal(xstream, campaign);
                summary.saveMs = elapsedMs(start);
                summary.marshalledBytes = first.length;
                LOGGER.info(String.format(Locale.ROOT, "[SaveBench] marshal: %.0f ms (%.1f MB)",
                        summary.saveMs, first.length / 1e6));

                if (MODE_ROUNDTRIP.equals(mode)) {
                    // ---- roundtrip: unmarshal 回读 marshal 产物，再 marshal 比对字节 ----
                    start = System.nanoTime();
                    final Object reloaded = xstream.fromXML(new ByteArrayInputStream(first));
                    summary.reloadMs = elapsedMs(start);
                    start = System.nanoTime();
                    final byte[] second = marshal(xstream, reloaded);
                    summary.remarshalMs = elapsedMs(start);
                    summary.roundtripIdentical = java.util.Arrays.equals(first, second);
                    LOGGER.info("[SaveBench] roundtrip: reload=" + summary.reloadMs + " ms"
                            + " remarshal=" + summary.remarshalMs + " ms"
                            + " identical=" + summary.roundtripIdentical);
                }
            }

            writeSummary(outputDir, summary);
        } finally {
            if (profiler != null) {
                profiler.stopAndDump();
            }
        }
    }

    /**
     * 遮蔽游戏的 CampaignEngineConverter（离线专用分歧点）。
     *
     * <p>游戏版 converter 的 {@code instantiateNewInstance} 在反序列化入口做运行期接线：
     * {@code CampaignEngine.setInstance} → {@code CombatEngine.getInstance()} → 构造器
     * 加载默认背景纹理 → lwjgl native，离线必炸。此处注册一个更高优先级的纯
     * {@link ReflectionConverter}（Unsafe 实例化、无任何单例接线）遮蔽它。
     * 序列化语义不变：游戏版继承 ReflectionConverter 且 marshal 路径无任何重写，
     * 两者编组行为一致；反序列化仅差异在「不触发 GL 单例」，字段填充逻辑相同。</p>
     */
    private static void shadowCampaignEngineConverter(final XStream xstream) {
        xstream.registerConverter(new ReflectionConverter(xstream.getMapper(), xstream.getReflectionProvider()) {
            @Override
            public boolean canConvert(final Class type) {
                return com.fs.starfarer.campaign.CampaignEngine.class.equals(type);
            }
        }, XStream.PRIORITY_VERY_HIGH);
    }

    /** 判定异常链根因是否为「当前线程无 GL 上下文」（lwjgl GLContext 检查抛出）。 */
    private static boolean isMissingGlContext(final Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains("No OpenGL context found in the current thread")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Path resolveSaveDir(final Path gameDir, final String saveDirProp) {
        final Path direct = Path.of(saveDirProp);
        if (direct.isAbsolute()) {
            return direct;
        }
        return gameDir.resolve("saves").resolve(saveDirProp);
    }

    private static SaveGameData readDescriptor(final XStream xstream, final Path saveDir) throws Exception {
        final Path descriptor = saveDir.resolve("descriptor.xml");
        try (InputStream in = Files.newInputStream(descriptor)) {
            return (SaveGameData) xstream.fromXML(in);
        }
    }

    /** 读取战役主档（明文 campaign.xml 或 zip 容器内同名条目）。 */
    private static Object readCampaign(final XStream xstream, final Path saveDir) throws Exception {
        final Path plain = saveDir.resolve("campaign.xml");
        if (Files.isRegularFile(plain)) {
            // SSOZ1 zstd 字段回译为原版 deflate 格式（离线无 Mixin，见 SavePayloadNormalizer）
            final Path normalized = SavePayloadNormalizer.normalizeToLegacyFile(plain);
            try (InputStream in = Files.newInputStream(normalized)) {
                return xstream.fromXML(in);
            }
        }
        final Path zipped = saveDir.resolve("campaign.zip");
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipped))) {
            if (zip.getNextEntry() == null) {
                throw new IllegalStateException("campaign.zip 为空: " + zipped);
            }
            return xstream.fromXML(zip);
        }
    }

    private static long payloadSize(final Path saveDir) throws Exception {
        final Path plain = saveDir.resolve("campaign.xml");
        if (Files.isRegularFile(plain)) {
            return Files.size(plain);
        }
        return Files.size(saveDir.resolve("campaign.zip"));
    }

    private static byte[] marshal(final XStream xstream, final Object campaign) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 26);
        xstream.toXML(campaign, out);
        return out.toByteArray();
    }

    private static double elapsedMs(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    private static void writeSummary(final Path outputDir, final Summary s) throws Exception {
        final String json = "{\n"
                + "  \"saveDir\": \"" + s.saveDir.replace("\\", "\\\\") + "\",\n"
                + "  \"mode\": \"" + s.mode + "\",\n"
                + "  \"characterName\": \"" + (s.characterName == null ? "" : s.characterName) + "\",\n"
                + "  \"saveFileVersion\": \"" + s.saveFileVersion + "\",\n"
                + "  \"payloadBytes\": " + s.payloadBytes + ",\n"
                + "  \"descriptorMs\": " + s.descriptorMs + ",\n"
                + "  \"loadMs\": " + s.loadMs + ",\n"
                + "  \"saveMs\": " + s.saveMs + ",\n"
                + "  \"marshalledBytes\": " + s.marshalledBytes + ",\n"
                + "  \"reloadMs\": " + s.reloadMs + ",\n"
                + "  \"remarshalMs\": " + s.remarshalMs + ",\n"
                + "  \"roundtripIdentical\": " + s.roundtripIdentical + "\n"
                + "}\n";
        final Path out = outputDir.resolve("savebench-summary.json");
        Files.writeString(out, json);
        LOGGER.info("[SaveBench] summary written: " + out.toAbsolutePath());
        // 同步打到 stdout，供脚本 grep
        System.out.print("[SaveBench-Summary] " + json.replace("\n", " "));
    }

    private static final class Summary {
        private String saveDir;
        private String mode;
        private String characterName;
        private String saveFileVersion;
        private long payloadBytes;
        private double descriptorMs;
        private double loadMs;
        private double saveMs;
        private long marshalledBytes;
        private double reloadMs;
        private double remarshalMs;
        private boolean roundtripIdentical;
    }
}
