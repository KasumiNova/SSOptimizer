package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArchiveEntries {
    static final String METADATA = "META-INF/ssoptimizer-remap.properties";
    static final String NAMESPACE = "SSOptimizer-Namespace";

    private ArchiveEntries() {
    }

    static boolean isNamed(final Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            final var entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) {
                return false;
            }
            try (var stream = zip.getInputStream(entry)) {
                return "named".equals(new Manifest(stream).getMainAttributes().getValue(NAMESPACE));
            }
        }
    }

    static Set<String> validate(final Path path, final boolean obfuscated,
                                final java.util.function.BiConsumer<String, byte[]> classes) throws IOException {
        final Set<String> entries = new HashSet<>();
        final Set<String> classEntries = new HashSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            final var manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (obfuscated && manifestEntry != null) {
                try (var stream = zip.getInputStream(manifestEntry)) {
                    final Manifest manifest = new Manifest(stream);
                    if ("named".equals(manifest.getMainAttributes().getValue(NAMESPACE))) {
                        throw new PreparationException.Input("Already named archive was supplied for obfuscated preparation: " + path);
                    }
                }
            }
            final var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                final ZipEntry entry = iterator.nextElement();
                final String name = entry.getName();
                validateName(name);
                if (!entries.add(name)) {
                    throw new PreparationException.Conflict("Duplicate ZIP entry in " + path + ": " + name);
                }
                if (obfuscated && name.equals(METADATA)) {
                    throw new PreparationException.Input("Archive already contains remap provenance: " + path);
                }
                if (entry.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                final byte[] bytes;
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                final ClassReader reader = new ClassReader(bytes);
                if (!className(name).equals(reader.getClassName())) {
                    throw new PreparationException.Input("Class entry/name mismatch in " + path + ": " + name + " contains " + reader.getClassName());
                }
                classEntries.add(name);
                classes.accept(name, bytes);
            }
        } catch (IllegalArgumentException error) {
            throw new PreparationException.Input("Invalid class archive " + path + ": " + error.getMessage(), error);
        }
        return Set.copyOf(classEntries);
    }

    static void validateName(final String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.contains("//") || name.contains(":")
                || java.util.Arrays.stream(name.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new PreparationException.Input("Unsafe archive entry: " + name);
        }
    }

    static int release(final String entry) {
        if (!entry.startsWith("META-INF/versions/")) {
            return 0;
        }
        final int end = entry.indexOf('/', 18);
        if (end < 0) {
            throw new IllegalArgumentException("Invalid multi-release class entry: " + entry);
        }
        final int release = Integer.parseInt(entry.substring(18, end));
        if (release < 9) {
            throw new IllegalArgumentException("Invalid multi-release version: " + entry);
        }
        return release;
    }

    static String className(final String entry) {
        final int release = release(entry);
        final String path = release == 0 ? entry : entry.substring(entry.indexOf('/', 18) + 1);
        return path.substring(0, path.length() - 6);
    }

    static String mappedClassEntry(final String entry, final MappingProjection mapping) {
        final int release = release(entry);
        return (release == 0 ? "" : "META-INF/versions/" + release + "/") + mapping.mapClass(className(entry)) + ".class";
    }

    static boolean invalidSignature(final String name) {
        final String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.substring(9).contains("/")) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-") || upper.equals("META-INF/INDEX.LIST");
    }

    static void stripDigests(final Attributes attributes) {
        attributes.keySet().removeIf(key -> {
            final String name = key.toString().toUpperCase(Locale.ROOT);
            return name.contains("-DIGEST") || name.equals("MAGIC");
        });
    }
}
