package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 按游戏子 JVM 实际搜索次序保留第一个有效定义。不同归档之间以 named 二进制名比较。 */
final class ModClassFilter {
    static final String REPORT = "class-filter.log";
    private record Owner(Path archive, String entry) {
        @Override public String toString() { return archive + "!/" + entry; }
    }
    record Site(Path archive, int ordinal) {}
    static final class Unusable extends IOException {
        final Map<Site,String> entries;
        Unusable(Map<Site,String> entries) { super("One or more mod entries failed transformation"); this.entries = Map.copyOf(entries); }
    }
    private final MappingProjection mapping;
    private final Map<Site,String> excluded;
    private final Map<Owner,Site> transformed = new HashMap<>();
    private final Map<Site,String> failed = new HashMap<>();
    private final Map<String, Owner> owners = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int duplicates, invalid;

    ModClassFilter(MappingProjection mapping, Map<Site,String> excluded) { this.mapping = mapping; this.excluded = excluded; }

    void reserve(Path original, Path snapshot, boolean named) throws IOException {
        try (ZipFile zip = new ZipFile(snapshot.toFile())) {
            boolean multi = ArchiveEntries.multiRelease(zip);
            for (var it = zip.entries(); it.hasMoreElements();) {
                String name = it.nextElement().getName();
                if (!name.endsWith(".class") || ModuleMetadata.isDescriptor(name) || !ArchiveEntries.visible(name, multi)) continue;
                String logical = ArchiveEntries.className(name);
                owners.putIfAbsent(named ? logical : mapping.mapClass(logical), new Owner(original, name));
            }
        }
    }

    /** 只在需要移除条目时创建新文件；重复 ZIP 名称按中央目录次序区分。 */
    Path select(Path original, Path snapshot, boolean named, Path filtered) throws IOException {
        BitSet removed = new BitSet();
        Map<String, String> local = new HashMap<>();
        Set<String> keptEntries = new HashSet<>(), mappedEntries = new HashSet<>(), resources = new HashSet<>();
        try (ZipFile zip = new ZipFile(snapshot.toFile())) {
            boolean multi = ArchiveEntries.multiRelease(zip);
            int index = 0;
            int manifestCount = 0;
            for (var it = zip.entries(); it.hasMoreElements(); index++) {
                ZipEntry item = it.nextElement();
                String name = item.getName();
                ArchiveEntries.validateName(name);
                if (!name.endsWith(".class")) {
                    if (ArchiveEntries.isManifest(name)) manifestCount++;
                    if (!resources.add(name)) throw new PreparationException.Conflict("Duplicate resource: " + original + "!/" + name);
                    try (var in = zip.getInputStream(item)) {
                        var checked = new java.util.zip.CheckedInputStream(in, new java.util.zip.CRC32());
                        long length = checked.transferTo(java.io.OutputStream.nullOutputStream());
                        checkZip(item, length, checked.getChecksum().getValue());
                    } catch (IOException ex) { throw new IOException(original + "!/" + name + ": " + ex.getMessage(), ex); }
                    continue;
                }
                Owner here = new Owner(original, name);
                Site site = new Site(original, index);
                String priorFailure = excluded.get(site);
                if (priorFailure != null) { invalid(here, priorFailure); removed.set(index); continue; }
                byte[] bytes;
                try (var in = zip.getInputStream(item)) {
                    var checked = new java.util.zip.CheckedInputStream(in, new java.util.zip.CRC32());
                    bytes = checked.readAllBytes();
                    checkZip(item, bytes.length, checked.getChecksum().getValue());
                } catch (java.util.zip.ZipException | java.io.EOFException ex) {
                    invalid(here, ex); removed.set(index); continue;
                }
                try {
                    String raw = ArchiveEntries.className(name);
                    boolean descriptor = raw.equals("module-info");
                    if (!descriptor && !ArchiveEntries.visible(name, multi)) {
                        if (!keptEntries.add(name)) { duplicate(here, here); removed.set(index); }
                        continue;
                    }
                    validateClass(bytes, raw, named);
                    String target = named || descriptor ? raw : mapping.mapClass(raw);
                    String variant = ArchiveEntries.release(name) + ":" + target;
                    Owner previous = descriptor ? null : owners.get(target);
                    if (previous != null && (!previous.archive.equals(original) || !raw.equals(local.get(target)))) {
                        duplicate(here, previous); removed.set(index); continue;
                    }
                    if (!mappedEntries.add(variant)) {
                        duplicate(here, previous == null ? here : previous); removed.set(index); continue;
                    }
                    keptEntries.add(name);
                    if (!named) transformed.put(new Owner(original, ArchiveEntries.mappedClassEntry(name, mapping)), site);
                    if (!descriptor) {
                        owners.putIfAbsent(target, here);
                        local.put(target, raw);
                    }
                } catch (RuntimeException ex) {
                    invalid(here, ex); removed.set(index);
                }
            }
            if (removed.isEmpty() && manifestCount <= 1) return snapshot;
            try (var out = new ZipOutputStream(Files.newOutputStream(filtered))) {
                var manifest = ArchiveEntries.manifestEntry(zip);
                if (manifest != null) {
                    try (var in = zip.getInputStream(manifest)) {
                        var mf = new Manifest(in);
                        ArchiveEntries.stripDigests(mf.getMainAttributes());
                        mf.getEntries().values().forEach(ArchiveEntries::stripDigests);
                        var entry = new ZipEntry(java.util.jar.JarFile.MANIFEST_NAME);
                        entry.setTime(0);
                        out.putNextEntry(entry);
                        mf.write(out);
                        out.closeEntry();
                    }
                }
                int i = 0;
                for (var it = zip.entries(); it.hasMoreElements(); i++) {
                    ZipEntry item = it.nextElement();
                    String name = item.getName();
                    if (removed.get(i) || ArchiveEntries.invalidSignature(name) || ArchiveEntries.isManifest(name)) continue;
                    var dest = new ZipEntry(name); dest.setTime(0); out.putNextEntry(dest);
                    try (var in = zip.getInputStream(item)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
        }
        return filtered;
    }

    private static void checkZip(ZipEntry entry, long length, long crc) throws java.util.zip.ZipException {
        if (length != entry.getSize() || crc != entry.getCrc()) throw new java.util.zip.ZipException("ZIP size/CRC mismatch");
    }

    private void validateClass(byte[] bytes, String expected, boolean named) {
        if (bytes.length < 10 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe)
            throw new IllegalArgumentException("Invalid class magic or truncated header");
        int major = (bytes[6] & 255) << 8 | bytes[7] & 255;
        if (major < 45 || major > Runtime.version().feature() + 44) throw new IllegalArgumentException("Unsupported class version: " + major);
        ClassReader reader = new ClassReader(bytes);
        if (!expected.equals(reader.getClassName())) throw new IllegalArgumentException("Entry/name mismatch: " + reader.getClassName());
        var node = new ClassNode();
        reader.accept(node, 0);
        if (expected.equals("module-info") && node.module == null) throw new IllegalArgumentException("Missing Module attribute");
        ModClassStructure.check(bytes, node);
        Set<String> fields = new HashSet<>(), methods = new HashSet<>();
        for (var f : node.fields) checkMember(fields, f.name, f.desc, false, named);
        for (var m : node.methods) checkMember(methods, m.name, m.desc, true, named);
        if (!named) ClassNameCalls.rewrite(node, mapping);
        // 重建方法体会访问跳转目标、常量引用和属性；只读取类头无法发现这些损坏。
        node.accept(new ClassWriter(0));
    }

    private static void checkMember(Set<String> seen, String name, String desc, boolean method, boolean named) {
        String normalized = BytecodeRepair.memberName(name, method);
        if (named && !name.equals(normalized)) throw new IllegalArgumentException("Invalid member name: " + name);
        if (!seen.add(normalized + '\0' + desc)) throw new IllegalArgumentException("Duplicate member: " + name + desc);
    }

    private void duplicate(Owner here, Owner previous) {
        duplicates++;
        warnings.add("跳过重复类 " + here + "；优先定义 " + previous);
    }

    void transformedFailure(Path source, String entry, RuntimeException error) {
        Site site = transformed.get(new Owner(source, entry));
        if (site == null) throw new IllegalStateException("Cannot identify transformed input: " + source + "!/" + entry, error);
        failed.put(site, error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    void checkTransformed() throws Unusable { if (!failed.isEmpty()) throw new Unusable(failed); }

    private void invalid(Owner here, Exception error) {
        invalid(here, error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private void invalid(Owner here, String reason) {
        invalid++;
        warnings.add("跳过无效类 " + here + "；" + reason);
    }

    void writeReport(Path directory) throws IOException {
        var lines = new ArrayList<>(warnings);
        lines.add("模组类过滤：重复 " + duplicates + "，无效 " + invalid + "，共跳过 " + warnings.size() + " 个条目。");
        Files.write(directory.resolve(REPORT), lines);
    }
}
