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

        final List<? extends com.fs.starfarer.api.ModSpecAPI> enabledMods = GameEnvBootstrap.setup(gameDir);
        ScriptClassLoaderSetup.begin(gameDir, enabledMods);
        final List<ModPlugin> plugins = ModPluginBootstrap.setup();

        // 哑 ResourceLoaderState 仅收集离线丢弃的预载队列，避免 loadMissions 空指针。
        ShipNameStore.loadNames();
        StarfarerStrings.load();
        long start = System.nanoTime();
        SpecStore.loadStarmap(new ResourceLoaderState());
        LOGGER.info(String.format(Locale.ROOT, "[SaveBench] spec store loaded: %.0f ms", elapsedMs(start)));
        MarkovNames.loadIfNeeded();
        ScriptClassLoaderSetup.finish();
        // 仅跳过无 GL 上下文或未 remap 导致的插件初始化失败，其余异常继续抛出。
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

            start = System.nanoTime();
            final SaveGameData descriptor = readDescriptor(xstream, saveDir);
            summary.descriptorMs = elapsedMs(start);
            summary.characterName = descriptor.getCharacterName();
            summary.saveFileVersion = descriptor.getSaveFileVersion();
            LOGGER.info("[SaveBench] descriptor: " + descriptor.getCharacterName()
                    + " version=" + descriptor.getSaveFileVersion()
                    + " mods=" + descriptor.getEnabledMods().size());

            start = System.nanoTime();
            final Object campaign = readCampaign(xstream, saveDir);
            summary.loadMs = elapsedMs(start);
            summary.payloadBytes = payloadSize(saveDir);
            LOGGER.info(String.format(Locale.ROOT, "[SaveBench] load: %.0f ms (%.1f MB)",
                    summary.loadMs, summary.payloadBytes / 1e6));

            if (MODE_SAVE.equals(mode) || MODE_ROUNDTRIP.equals(mode)) {
                start = System.nanoTime();
                final byte[] first = marshal(xstream, campaign);
                summary.saveMs = elapsedMs(start);
                summary.marshalledBytes = first.length;
                LOGGER.info(String.format(Locale.ROOT, "[SaveBench] marshal: %.0f ms (%.1f MB)",
                        summary.saveMs, first.length / 1e6));

                if (MODE_ROUNDTRIP.equals(mode)) {
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

    /** 用纯 ReflectionConverter 避免游戏转换器在离线反序列化时初始化 GL 单例。 */
    private static void shadowCampaignEngineConverter(final XStream xstream) {
        xstream.registerConverter(new ReflectionConverter(xstream.getMapper(), xstream.getReflectionProvider()) {
            @Override
            public boolean canConvert(final Class type) {
                return com.fs.starfarer.campaign.CampaignEngine.class.equals(type);
            }
        }, XStream.PRIORITY_VERY_HIGH);
    }

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

    private static Object readCampaign(final XStream xstream, final Path saveDir) throws Exception {
        final Path plain = saveDir.resolve("campaign.xml");
        if (Files.isRegularFile(plain)) {
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
