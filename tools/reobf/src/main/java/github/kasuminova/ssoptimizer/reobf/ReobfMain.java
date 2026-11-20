package github.kasuminova.ssoptimizer.reobf;

import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ReobfMain {
    private ReobfMain() {}

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        ReobfMappings mappings = ReobfMappings.read(options.mapping());
        GameClasses original = GameClasses.read(options.game());
        Path output = options.output().toAbsolutePath();
        Files.createDirectories(output.getParent());
        Path input = Files.createTempFile(output.getParent(), ".reobf-classes-", ".jar");
        try {
            try (ZipFile archive = new ZipFile(options.input().toFile())) {
                Set<String> classes = copyProjectClasses(archive, input);
                Map<String, byte[]> remapped = remap(input, options, mappings, original);
                if (!classes.equals(remapped.keySet())) {
                    throw new IOException("Class names changed during project JAR reobfuscation: input="
                            + difference(classes, remapped.keySet()) + ", output=" + difference(remapped.keySet(), classes));
                }
                for (Map.Entry<String, byte[]> entry : remapped.entrySet()) {
                    validate(entry.getValue(), mappings, original);
                }
                writeArchive(archive, output, remapped);
                System.out.println("Reobfuscated " + classes.size() + " project classes: " + output);
            }
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static void validate(byte[] content, ReobfMappings mappings, GameClasses original) {
        var node = new ClassNode();
        new ClassReader(content).accept(node, 0);
        MixinSymbols.validate(node, mappings, original);
        node.accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) {}, new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String name) {
                if (compileTimeConstants(name)) {
                    throw new IllegalArgumentException(node.name + " references a compile-time symbol holder: " + name);
                }
                if (mappings.isNamedGameClass(name) && !original.contains(name) && !mappings.className(name).equals(name)) {
                    throw new IllegalArgumentException(node.name + " references a named game class: " + name);
                }
                return name;
            }
        }));
    }

    private static Map<String, byte[]> remap(Path input, Options options, ReobfMappings mappings, GameClasses original) {
        var builder = TinyRemapper.newRemapper().withMappings(mappings.provider())
                .ignoreConflicts(false)
                .ignoreFieldDesc(false)
                .threads(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())))
                .extraStateProcessor(mappings::bind)
                .extraPreApplyVisitor((type, next) -> new ClassNode(Opcodes.ASM9) {
                    @Override
                    public void visitEnd() {
                        super.visitEnd();
                        MixinSymbols.rewriteAliases(this, mappings);
                        RuntimeStrings.rewrite(this, mappings, original);
                        accept(next);
                    }
                })
                .extension(new MixinExtension());
        var remapper = builder.build();
        try {
            Set<Path> classpath = new LinkedHashSet<>(options.named());
            classpath.addAll(options.classpath());
            remapper.readClassPath(classpath.toArray(Path[]::new));
            remapper.readInputs(input);
            var changed = new ConcurrentHashMap<String, byte[]>();
            remapper.apply((name, code) -> {
                String entryName = (name.startsWith("/") ? name.substring(1) : name) + ".class";
                if (changed.putIfAbsent(entryName, code) != null) {
                    throw new IllegalArgumentException("Duplicate reobfuscated class: " + entryName);
                }
            });
            return changed;
        } finally {
            remapper.finish();
        }
    }

    private static Set<String> copyProjectClasses(ZipFile source, Path input) throws IOException {
        var names = new LinkedHashSet<String>();
        try (var output = new ZipOutputStream(Files.newOutputStream(input))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!projectClass(entry.getName())) continue;
                if (!names.add(entry.getName())) throw new IOException("Duplicate project class: " + entry.getName());
                writeEntry(source, output, entry, null);
            }
        }
        if (names.isEmpty()) throw new IOException("No project classes found in " + source.getName());
        return names;
    }

    private static void writeArchive(ZipFile source, Path target, Map<String, byte[]> changed) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".reobf-output-", ".jar");
        try {
            try (var output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                var entries = source.entries();
                var names = new HashSet<String>();
                var originalManifest = source.getEntry("META-INF/MANIFEST.MF");
                Manifest manifest;
                if (originalManifest == null) {
                    originalManifest = new ZipEntry("META-INF/MANIFEST.MF");
                    manifest = new Manifest();
                } else {
                    try (var stream = source.getInputStream(originalManifest)) {
                        manifest = new Manifest(stream);
                    }
                }
                Attributes attributes = manifest.getMainAttributes();
                attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
                attributes.putValue("SSOptimizer-Namespace", "obf");
                removeDigests(attributes);
                manifest.getEntries().values().forEach(ReobfMain::removeDigests);
                var manifestBytes = new ByteArrayOutputStream();
                manifest.write(manifestBytes);
                writeEntry(source, output, originalManifest, manifestBytes.toByteArray());
                names.add(originalManifest.getName());
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.equals(originalManifest.getName()) || signature(name) || removedClass(name)) continue;
                    if (!names.add(name)) throw new IOException("Duplicate JAR entry: " + name);
                    writeEntry(source, output, entry, changed.get(name));
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeEntry(ZipFile source, ZipOutputStream target, ZipEntry original, byte[] bytes) throws IOException {
        var entry = new ZipEntry(original.getName());
        entry.setTime(original.getTime());
        target.putNextEntry(entry);
        if (bytes != null) {
            target.write(bytes);
        } else if (!original.isDirectory()) {
            try (var input = source.getInputStream(original)) {
                input.transferTo(target);
            }
        }
        target.closeEntry();
    }

    private static boolean projectClass(String entry) {
        if (!entry.endsWith(".class")) return false;
        if (entry.startsWith("META-INF/versions/")) {
            int nameStart = entry.indexOf('/', "META-INF/versions/".length());
            if (nameStart < 0) return false;
            entry = entry.substring(nameStart + 1);
        }
        return !compileTimeConstants(entry.substring(0, entry.length() - ".class".length()))
                && (entry.startsWith("github/kasuminova/ssoptimizer/") || entry.startsWith("ssoptimizer/standalone/"));
    }

    private static boolean compileTimeConstants(String className) {
        String namespace = "github/kasuminova/ssoptimizer/mapping/";
        if (!className.startsWith(namespace)) return false;
        String simpleName = className.substring(namespace.length());
        int inner = simpleName.indexOf('$');
        return switch (inner < 0 ? simpleName : simpleName.substring(0, inner)) {
            case "GameClassNames", "GameMemberNames", "GameMixinSignatures" -> true;
            default -> false;
        };
    }

    private static boolean removedClass(String entry) {
        if (!entry.endsWith(".class")) return false;
        if (entry.startsWith("META-INF/versions/")) {
            int nameStart = entry.indexOf('/', "META-INF/versions/".length());
            if (nameStart < 0) return false;
            entry = entry.substring(nameStart + 1);
        }
        return compileTimeConstants(entry.substring(0, entry.length() - ".class".length()));
    }

    private static void removeDigests(Attributes attributes) {
        attributes.keySet().removeIf(key -> {
            String name = key.toString().toUpperCase(Locale.ROOT);
            return name.equals("SIGNATURE-VERSION") || name.endsWith("-DIGEST") || name.contains("-DIGEST-");
        });
    }

    private static boolean signature(String path) {
        String upper = path.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String name = upper.substring("META-INF/".length());
        return name.indexOf('/') < 0 && (name.startsWith("SIG-") || name.endsWith(".SF") || name.endsWith(".RSA")
                || name.endsWith(".DSA") || name.endsWith(".EC"));
    }

    private static Set<String> difference(Set<String> first, Set<String> second) {
        var result = new LinkedHashSet<>(first);
        result.removeAll(second);
        return result;
    }

    private record Options(Path mapping, List<Path> named, List<Path> game, List<Path> classpath, Path input, Path output) {
        static Options parse(String[] args) throws IOException {
            var values = new LinkedHashMap<String, List<Path>>();
            Set<String> supported = Set.of("--mapping", "--named", "--game", "--classpath", "--input", "--output");
            if (args.length % 2 != 0) throw new IOException("Expected a path after " + args[args.length - 1]);
            for (int i = 0; i < args.length; i += 2) {
                if (!supported.contains(args[i])) throw new IOException("Unknown reobfuscation option: " + args[i]);
                values.computeIfAbsent(args[i], ignored -> new ArrayList<>()).add(Path.of(args[i + 1]));
            }
            Path input = one(values, "--input").toAbsolutePath().normalize();
            Path output = one(values, "--output").toAbsolutePath().normalize();
            if (input.equals(output) || Files.exists(output) && Files.isSameFile(input, output)) {
                throw new IOException("Input and output JAR paths must be distinct: " + input);
            }
            return new Options(one(values, "--mapping"), required(values, "--named"), required(values, "--game"),
                    List.copyOf(values.getOrDefault("--classpath", List.of())), input, output);
        }

        private static List<Path> required(Map<String, List<Path>> values, String option) throws IOException {
            List<Path> paths = values.get(option);
            if (paths == null) throw new IOException("Missing required reobfuscation option: " + option);
            return List.copyOf(paths);
        }

        private static Path one(Map<String, List<Path>> values, String option) throws IOException {
            List<Path> paths = required(values, option);
            if (paths.size() != 1) throw new IOException("Expected one path for " + option);
            return paths.getFirst();
        }
    }
}
