package ssoptimizer.standalone.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;

public final class MigrateNamedSlot {
    private static final List<String> GAME = List.of("starfarer_obf.jar", "starfarer.api.jar", "fs.common_obf.jar", "fs.sound_obf.jar");

    private MigrateNamedSlot() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !args[0].equals("--from-18e67d2")) {
            throw new IllegalArgumentException("用法：--from-18e67d2 <写档实例的 launch.properties> <私有 game-full.tiny.gz> <原版核心目录> <源槽> <新槽>");
        }
        Path plan = Path.of(args[1]).toRealPath(), tiny = Path.of(args[2]).toRealPath();
        Path core = Path.of(args[3]).toRealPath(), source = Path.of(args[4]).toRealPath();
        Path target = InstanceFiles.prospective(Path.of(args[5]));
        InstanceFiles.separate(source, target);
        InstanceFiles.separate(core, target);
        if (Files.exists(target, InstanceFiles.NO_LINK)) throw new IOException("目标槽已存在：" + target);
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(plan)) { p.load(reader); }
        var mappingHash = MessageDigest.getInstance("SHA-256");
        try (var in = new GZIPInputStream(Files.newInputStream(tiny)); var out = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), mappingHash)) {
            in.transferTo(out);
        }
        String mapping = HexFormat.of().formatHex(mappingHash.digest());
        if (!"4".equals(p.getProperty("game.count")) || !"rc8".equals(p.getProperty("profile"))) throw new IOException("需要 RC8 写档实例的四个游戏归档：" + plan);
        var writer = new ArrayList<Path>();
        var original = new ArrayList<Path>();
        for (int i = 0; i < GAME.size(); i++) addPair(path(p, "game." + i), core.resolve(GAME.get(i)), mapping, writer, original);
        int count = Integer.parseInt(p.getProperty("mod.count"));
        for (int i = 0; i < count; i++) addPair(path(p, "mod." + i), path(p, "mod.source." + i), mapping, writer, original);
        var symbols = SaveSymbols.read(tiny).reverse(writer, original, value -> {
            try { return github.kasuminova.ssoptimizer.common.save.TerrainTileCompressionHelper.toLegacyDeflateFormat(value); }
            catch (java.util.zip.DataFormatException e) { throw new IOException("旧存档地形编码无效", e); }
        });
        Path stage = Files.createTempDirectory(target.getParent(), ".named-migration-");
        try {
            var record = new Properties();
            record.setProperty("writer.commit", "18e67d2");
            record.setProperty("writer.plan", plan.toString());
            record.setProperty("writer.plan.sha256", InstanceFiles.digest(plan));
            record.setProperty("source", source.toString());
            record.setProperty("mapping.uncompressed.sha256", mapping);
            for (int i = 0; i < writer.size(); i++) {
                record.setProperty("writer.archive." + i, InstanceFiles.digest(writer.get(i)));
                record.setProperty("original.archive." + i, InstanceFiles.digest(original.get(i)));
            }
            copySlot(source, stage);
            var before = snapshot(stage);
            convert(stage, symbols);
            if (!before.equals(snapshot(source))) throw new IOException("迁移期间源槽发生变化：" + source);
            before.forEach((file, hash) -> record.setProperty("source.sha256." + file, hash));
            snapshot(stage).forEach((file, hash) -> record.setProperty("target.sha256." + file, hash));
            try (var out = Files.newOutputStream(stage.resolve(".ssoptimizer-named-migration.properties"), StandardOpenOption.CREATE_NEW)) {
                record.store(out, null);
            }
            Files.move(stage, target);
            System.out.println(target);
        } catch (IOException failure) {
            throw new IOException("迁移源槽失败：" + source + "，写档清单：" + plan + "，" + failure.getMessage(), failure);
        } finally {
            if (Files.exists(stage, InstanceFiles.NO_LINK)) InstanceFiles.deletePrivateTree(stage);
        }
    }

    private static java.util.Map<String, String> snapshot(Path root) throws IOException {
        var hashes = new java.util.TreeMap<String, String>();
        try (var tree = Files.walk(root)) {
            for (var it = tree.iterator(); it.hasNext();) {
                var path = it.next();
                if (Files.isDirectory(path, InstanceFiles.NO_LINK)) continue;
                InstanceFiles.regular(path);
                hashes.put(root.relativize(path).toString().replace(java.io.File.separatorChar, '/'), InstanceFiles.digest(path));
            }
        }
        return hashes;
    }

    private static Path path(Properties values, String key) throws IOException {
        String value = values.getProperty(key);
        if (value == null || !Path.of(value).isAbsolute()) throw new IOException("旧启动清单缺少绝对路径：" + key);
        return Path.of(value).toRealPath();
    }

    private static void addPair(Path named, Path raw, String mapping, List<Path> writer, List<Path> original) throws IOException {
        try (var jar = new JarFile(named.toFile())) {
            var manifest = jar.getManifest();
            if (manifest == null) throw new IOException("写档归档缺少来源清单：" + named);
            var attrs = manifest.getMainAttributes();
            if (!"named".equals(attrs.getValue("SSOptimizer-Namespace")) || !"ssoptimizer-named-v1".equals(attrs.getValue("SSOptimizer-Remap-ABI"))
                    || !mapping.equals(attrs.getValue("SSOptimizer-Mapping-SHA256"))
                    || !InstanceFiles.digest(raw).equals(attrs.getValue("SSOptimizer-Origin-SHA256"))) {
                throw new IOException("写档归档的命名空间、映射或原始 SHA-256 与目标不一致：" + named + " / " + raw);
            }
        }
        writer.add(named);
        original.add(raw);
    }

    private static void copySlot(Path source, Path stage) throws IOException {
        try (var tree = Files.walk(source)) {
            for (var it = tree.iterator(); it.hasNext();) {
                var file = it.next();
                if (file.equals(source)) continue;
                if (Files.isSymbolicLink(file) || file.getFileName().toString().endsWith(".inprogress")) throw new IOException("旧槽包含链接或未完成写入：" + file);
                var to = InstanceFiles.child(stage, source.relativize(file).toString().replace(java.io.File.separatorChar, '/'));
                if (Files.isDirectory(file, InstanceFiles.NO_LINK)) Files.createDirectory(to);
                else {
                    InstanceFiles.regular(file);
                    Files.copy(file, to);
                    if (Files.mismatch(file, to) != -1) throw new IOException("旧槽在复制期间发生变化：" + file);
                }
            }
        }
    }

    private static void convert(Path stage, SaveSymbols symbols) throws IOException {
        for (String suffix : List.of("", ".bak")) {
            Path descriptor = stage.resolve("descriptor.xml" + suffix);
            Path xml = stage.resolve("campaign.xml" + suffix), zip = stage.resolve("campaign.zip" + suffix);
            if (!suffix.isEmpty() && Files.notExists(descriptor) && Files.notExists(xml) && Files.notExists(zip)) continue;
            InstanceFiles.regular(descriptor);
            if (Files.size(descriptor) > 32L << 20) throw new IOException("旧档描述文件超过 32 MiB：" + descriptor);
            SaveDescriptor info;
            try (var in = Files.newInputStream(descriptor)) { info = SaveDescriptor.read(in); }
            Path campaign = info.compressed() ? zip : xml;
            InstanceFiles.regular(campaign);
            if (Files.exists(info.compressed() ? xml : zip)) throw new IOException("同一槽含两种战役编码：" + campaign);
            rewrite(descriptor, symbols, "SaveGameData");
            if (info.compressed()) {
                Path output = Files.createTempDirectory(stage, ".campaign-");
                Path dest = output.resolve("campaign.zip");
                HistoricalSave.convertZip(campaign, dest, symbols);
                Files.move(dest, campaign, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.delete(output);
            } else {
                if (Files.size(campaign) > 4L << 30) throw new IOException("战役超过 4 GiB：" + campaign);
                rewrite(campaign, symbols, "CampaignEngine");
            }
        }
    }

    private static void rewrite(Path file, SaveSymbols symbols, String root) throws IOException {
        Path out = Files.createTempFile(file.getParent(), ".migrate-", ".xml");
        try (var input = Files.newInputStream(file); var output = Files.newOutputStream(out, StandardOpenOption.WRITE)) {
            String result = SaveXml.convert(input, output, symbols, file.toString());
            if (!result.equals(root) && !result.equals("com.fs.starfarer.campaign." + (root.equals("SaveGameData") ? "save." : "") + root)) {
                throw new IOException("未识别存档根节点：" + result);
            }
        }
        Files.move(out, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
