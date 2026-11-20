package github.kasuminova.ssoptimizer.standalone.obf;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

final class JarSelection {
    static final String REPORT = "class-filter.log";
    private final Map<String,Path> owners = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int duplicates, invalid;
    private final GameMembers members;

    JarSelection(GameMembers members) { this.members = members; }

    void reserve(Path library) throws IOException {
        try (var jar = new JarFile(library.toFile(), false)) {
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                String path = entries.nextElement().getName();
                String name = className(path, jar.isMultiRelease());
                if (name != null && !name.equals("module-info")) owners.putIfAbsent(name, library);
            }
        }
    }

    boolean prepare(Path origin, Path snapshot, Path output, boolean mod) throws IOException {
        Map<Integer,byte[]> replacements = new HashMap<>();
        BitSet removed = new BitSet();
        Set<String> unique = new HashSet<>();
        String firstEdit = null;
        boolean signed = false;
        try (var jar = new JarFile(snapshot.toFile(), true)) {
            boolean multi = jar.isMultiRelease();
            int index = 0;
            for (var it = jar.entries(); it.hasMoreElements(); index++) {
                JarEntry entry = it.nextElement();
                String path = entry.getName();
                validatePath(path);
                signed |= signature(path);
                String name = className(path, multi);
                if (name == null) {
                    if (!unique.add(path)) throw new IOException("重复资源 " + origin + "!/" + path);
                    read(jar, entry, false);
                    signed |= entry.getCodeSigners() != null;
                    continue;
                }
                try {
                    byte[] input = read(jar, entry, true);
                    signed |= entry.getCodeSigners() != null;
                    byte[] fixed = ObfClasses.normalize(input, mod, name, members);
                    Path previous = owners.get(name);
                    boolean priorArchive = mod && previous != null && !previous.equals(origin) && !name.equals("module-info");
                    if (!unique.add(path) || priorArchive) {
                        if (!mod) throw new IOException("重复游戏条目 " + origin + "!/" + path);
                        removed.set(index);
                        duplicates++;
                        warnings.add("跳过重复类 " + origin + "!/" + path + "，优先来源 " + (previous == null ? origin : previous));
                    } else {
                        if (!name.equals("module-info")) owners.putIfAbsent(name, origin);
                        if (fixed != input) replacements.put(index, fixed);
                    }
                } catch (SecurityException signatureFailure) {
                    throw signatureFailure;
                } catch (RuntimeException | ZipException | EOFException failure) {
                    if (!mod) throw new IOException("游戏类损坏 " + origin + "!/" + path, failure);
                    invalid++;
                    removed.set(index);
                    warnings.add("跳过无效类 " + origin + "!/" + path + "，" + failure);
                }
                if (firstEdit == null && (replacements.containsKey(index) || removed.get(index))) firstEdit = path;
            }
            if (firstEdit == null) return false;
            if (signed) throw new IOException("签名归档需要修改条目，已拒绝生成副本 " + origin + "!/" + firstEdit);
            try (var out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)))) {
                index = 0;
                for (var it = jar.entries(); it.hasMoreElements(); index++) {
                    JarEntry entry = it.nextElement();
                    if (removed.get(index)) continue;
                    var target = new ZipEntry(entry.getName());
                    target.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                    out.putNextEntry(target);
                    byte[] bytes = replacements.get(index);
                    if (bytes != null) out.write(bytes);
                    else try (var in = jar.getInputStream(entry)) { in.transferTo(out); }
                    out.closeEntry();
                }
            }
        } catch (SecurityException e) {
            throw new IOException("归档签名校验失败 " + origin, e);
        }
        return true;
    }

    private static byte[] read(JarFile jar, JarEntry entry, boolean keep) throws IOException {
        var crc = new CRC32();
        try (var stream = new CheckedInputStream(jar.getInputStream(entry), crc)) {
            byte[] bytes = keep ? stream.readAllBytes() : null;
            long size = keep ? bytes.length : stream.transferTo(OutputStream.nullOutputStream());
            if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new ZipException("长度或 CRC 不符 " + entry.getName());
            return bytes;
        }
    }

    private static boolean signature(String path) {
        String upper = path.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.indexOf('/', 9) >= 0) return false;
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-");
    }

    private static void validatePath(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0 || path.contains("//")) {
            throw new IOException("无效归档路径 " + path);
        }
        for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) throw new IOException("无效归档路径 " + path);
    }

    private static String className(String path, boolean multi) {
        if (!path.endsWith(".class")) return null;
        if (path.startsWith("META-INF/")) {
            String prefix = "META-INF/versions/";
            if (!multi || !path.startsWith(prefix)) return null;
            int end = path.indexOf('/', prefix.length());
            if (end < 0) return null;
            String version = path.substring(prefix.length(), end);
            if (!version.matches("[1-9][0-9]?")) return null;
            int release = Integer.parseInt(version);
            if (release < 9 || release > Runtime.version().feature()) return null;
            path = path.substring(end + 1);
            if (path.startsWith("META-INF/")) return null;
        }
        return path.substring(0, path.length() - 6);
    }

    void writeReport(Path directory) throws IOException {
        warnings.add("模组类过滤：重复 " + duplicates + "，无效 " + invalid + "。");
        Files.write(directory.resolve(REPORT), warnings);
    }
}
