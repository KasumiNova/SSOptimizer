package ssoptimizer.standalone.instance;

import ssoptimizer.standalone.mods.ModCatalog;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static ssoptimizer.standalone.instance.InstanceFiles.NO_LINK;

public final class HistoricalSave {
    private static final String SOURCE = ".import-source";
    private static final String RECORD = ".standalone-import.properties";
    private static final long MAX_XML = 4L << 30;
    private static final long MAX_DESCRIPTOR = 32L << 20;

    private HistoricalSave() {}

    public static Path importSlot(Path sourceSlot, Path instance, String targetSlot, String confirmedSource,
                                  Path verifiedTiny) throws IOException {
        if (!"windows-en-rc8".equals(confirmedSource) && !"windows-zh-rc8".equals(confirmedSource)) {
            throw new IOException("请明确确认原版或 Miko 战役的受支持 RC8 写入来源：" + confirmedSource);
        }
        checkSlot(targetSlot);
        Path source = sourceSlot.toRealPath(), root = instance.toRealPath();
        InstanceFiles.separate(source, root);
        InstanceFiles.regular(source.resolve("descriptor.xml"));
        if (Files.size(source.resolve("descriptor.xml")) > MAX_DESCRIPTOR) throw new IOException("descriptor.xml 超过 32 MiB：" + source);
        InstanceFiles.regular(InstanceFiles.child(root, ".standalone-layout/installed.properties"));
        return InstanceLock.with(root, state -> {
            Path saves = InstanceFiles.directory(root, "saves");
            Path target = InstanceFiles.child(saves, targetSlot);
            if (Files.exists(target, NO_LINK)) throw new IOException("目标槽已存在，请指定新槽名：" + target);
            var registry = ModCopies.read(InstanceFiles.child(state, "installed.properties"));
            String gameRoot = registry.getProperty("@game");
            if (gameRoot == null) throw new IOException("实例缺少游戏来源：" + state);
            var game = ssoptimizer.standalone.launch.GameInstall.inspect(Path.of(gameRoot));
            if (!game.profile().equals(confirmedSource)) throw new IOException("确认的存档来源与已校验游戏不同：" + confirmedSource + " / " + game.profile());
            SaveSymbols symbols = SaveSymbols.read(verifiedTiny);
            symbols.bindGame(game.gameJars());
            Path stage = Files.createTempDirectory(state, "import-");
            try {
                Path originals = Files.createDirectory(stage.resolve(SOURCE));
                Properties record = new Properties();
                record.setProperty("source.path", source.toString());
                record.setProperty("source.profile", confirmedSource);
                record.setProperty("source.namespace", "obf");
                record.setProperty("target.namespace", "named");
                record.setProperty("mapping.sha256", InstanceFiles.digest(verifiedTiny));
                snapshot(source, stage, originals, record);
                Path descriptor = originals.resolve("descriptor.xml");
                InstanceFiles.regular(descriptor);
                if (Files.size(descriptor) > MAX_DESCRIPTOR) throw new IOException("descriptor.xml 超过 32 MiB：" + source);
                try (var in = Files.newInputStream(descriptor); var out = Files.newOutputStream(stage.resolve("descriptor.xml"), StandardOpenOption.CREATE_NEW)) {
                    SaveXml.convert(in, out, symbols, descriptor.toString());
                }
                SaveDescriptor info;
                try (var in = Files.newInputStream(stage.resolve("descriptor.xml"))) { info = SaveDescriptor.read(in); }
                var missing = new LinkedHashSet<>(info.mods());
                Path mods = InstanceFiles.child(root, "mods");
                var enabled = Set.copyOf(ModCatalog.enabledIds(mods));
                try (var dirs = Files.list(mods)) {
                    for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve("mod_info.json"))).toList()) {
                        String id = ModCatalog.modId(dir);
                        if (enabled.contains(id)) missing.remove(id);
                    }
                }
                if (!missing.isEmpty()) throw new IOException("旧存档 " + source + " 所需的模组尚未在实例安装并启用：" + missing);
                String name = info.compressed() ? "campaign.zip" : "campaign.xml";
                Path campaign = originals.resolve(name);
                InstanceFiles.regular(campaign);
                if (info.compressed()) {
                    convertZip(campaign, stage.resolve(name), symbols);
                } else {
                    if (Files.size(campaign) > MAX_XML) throw new IOException("campaign.xml 超过 4 GiB：" + source);
                    try (var in = Files.newInputStream(campaign); var out = Files.newOutputStream(stage.resolve(name), StandardOpenOption.CREATE_NEW)) {
                        campaign(in, out, symbols, campaign.toString());
                    }
                }
                try (var out = Files.newOutputStream(stage.resolve(RECORD), StandardOpenOption.CREATE_NEW)) { record.store(out, null); }
                Files.move(stage, target);
                return target;
            } catch (IOException e) {
                throw new IOException("旧档 " + source + "（" + confirmedSource + "）导入失败：" + e.getMessage(), e);
            } finally {
                if (Files.exists(stage, NO_LINK)) InstanceFiles.deletePrivateTree(stage);
            }
        });
    }

    private static void snapshot(Path source, Path stage, Path originals, Properties record) throws IOException {
        try (var paths = Files.walk(source)) {
            for (var it = paths.iterator(); it.hasNext();) {
                Path path = it.next();
                if (path.equals(source)) continue;
                String relative = source.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                String top = source.relativize(path).getName(0).toString();
                if (top.equalsIgnoreCase(SOURCE) || top.equalsIgnoreCase(RECORD)) throw new IOException("此槽已有 Standalone 导入记录：" + path);
                if (top.endsWith(".inprogress")) throw new IOException("游戏存档写入尚未完成：" + path);
                if (!path.toRealPath().equals(path) || Files.isSymbolicLink(path)) throw new IOException("存档路径含链接：" + path);
                boolean original = Set.of("descriptor.xml", "campaign.xml", "campaign.zip", "descriptor.xml.bak", "campaign.xml.bak", "campaign.zip.bak").contains(top);
                Path to = InstanceFiles.child(original ? originals : stage, relative);
                if (Files.isDirectory(path, NO_LINK)) {
                    Files.createDirectory(to);
                } else {
                    InstanceFiles.regular(path);
                    InstanceFiles.copyOwned(path, to);
                    if (Files.mismatch(path, to) != -1) throw new IOException("复制时源文件已改变：" + path);
                    if (original) record.setProperty("source.sha256." + relative, InstanceFiles.digest(to));
                }
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        for (String name : Set.of("descriptor.xml", "campaign.xml", "campaign.zip")) {
            Path file = source.resolve(name);
            String digest = record.getProperty("source.sha256." + name);
            if (digest == null ? Files.exists(file, NO_LINK) : !digest.equals(InstanceFiles.digest(file))) {
                throw new IOException("读取期间源存档已更新：" + file);
            }
        }
    }

    private static void checkSlot(String slot) throws IOException {
        if (slot == null || slot.isBlank() || slot.startsWith(".") || slot.endsWith(".") || slot.endsWith(" ")
                || slot.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
                || slot.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) {
            throw new IOException("无效的目标槽名：" + slot);
        }
    }

    private static void campaign(InputStream in, OutputStream out, SaveSymbols symbols, String file) throws IOException {
        String root = SaveXml.convert(in, out, symbols, file);
        if (!Set.of("CampaignEngine", "com.fs.starfarer.campaign.CampaignEngine").contains(root)) {
            throw new IOException(file + " 的根元素无法识别为 RC8 战役：" + root);
        }
    }

    static void convertZip(Path input, Path output, SaveSymbols symbols) throws IOException {
        try (ZipFile zip = new ZipFile(input.toFile())) {
            ZipEntry entry = zip.getEntry("campaign.xml");
            if (zip.size() != 1 || entry == null || entry.isDirectory() || entry.getSize() <= 0 || entry.getSize() > MAX_XML) {
                throw new IOException(input + " 必须仅包含 4 GiB 以内的 campaign.xml");
            }
            CRC32 crc = new CRC32();
            try (var raw = zip.getInputStream(entry);
                 var checked = new CheckedInputStream(new BoundedInput(new BufferedInputStream(raw), entry.getSize()), crc);
                 var out = new ZipOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))) {
                out.putNextEntry(new ZipEntry("campaign.xml"));
                campaign(checked, out, symbols, input + "!/campaign.xml");
                if (crc.getValue() != entry.getCrc()) throw new IOException(input + "!/campaign.xml CRC32 校验失败");
                out.closeEntry();
            }
        }
    }

    private static final class BoundedInput extends FilterInputStream {
        private long remaining;
        BoundedInput(InputStream in, long size) { super(in); remaining = size; }
        private int count(int n) throws IOException {
            if (n < 0 && remaining != 0 || n > 0 && (remaining -= n) < 0) throw new IOException("ZIP 中声明的 XML 长度与实际不符");
            return n;
        }
        @Override public int read() throws IOException { int value = super.read(); count(value == -1 ? -1 : 1); return value; }
        @Override public int read(byte[] b, int off, int len) throws IOException { return count(super.read(b, off, len)); }
    }
}
