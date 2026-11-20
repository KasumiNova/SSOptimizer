package github.kasuminova.ssoptimizer.standalone.remap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArchiveEntries {
    static final String NAMESPACE = "SSOptimizer-Namespace";

    private ArchiveEntries() {
    }

    static boolean isManifest(String name) {
        return name.length() == JarFile.MANIFEST_NAME.length()
                && name.chars().allMatch(c -> c < 128) && JarFile.MANIFEST_NAME.equalsIgnoreCase(name);
    }

    static ZipEntry manifestEntry(ZipFile zip) {
        ZipEntry exact = zip.getEntry(JarFile.MANIFEST_NAME);
        if (exact != null) return exact;
        for (var entries = zip.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            if (isManifest(entry.getName())) return entry;
        }
        return null;
    }

    static Attributes attributes(Path path) throws IOException {
        try (var jar = new JarFile(path.toFile(), false)) {
            var manifest = jar.getManifest();
            return manifest == null ? new Attributes() : manifest.getMainAttributes();
        }
    }

    interface ClassEntry {
        void read(String name, byte[] bytes) throws IOException;
    }

    static void readClasses(Path path, ClassEntry consumer) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            boolean multi = multiRelease(zip);
            for (var it = zip.entries(); it.hasMoreElements();) {
                var entry = it.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || !visible(name, multi) && !ModuleMetadata.isDescriptor(name)) continue;
                try (var in = zip.getInputStream(entry)) {
                    consumer.read(name, in.readAllBytes());
                }
            }
        }
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

    static boolean multiRelease(ZipFile zip) throws IOException {
        var entry = manifestEntry(zip);
        if (entry == null) return false;
        try (var in = zip.getInputStream(entry)) {
            return Boolean.parseBoolean(new Manifest(in).getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE));
        }
    }

    static boolean visible(String entry, boolean multi) {
        if (!entry.startsWith("META-INF/")) return true;
        if (!multi || !entry.startsWith("META-INF/versions/")) return false;
        int version = release(entry);
        return version <= Runtime.version().feature() && entry.startsWith("META-INF/versions/" + version + "/")
                && !className(entry).startsWith("META-INF/");
    }

    static String className(final String entry) {
        final int release = release(entry);
        final String path = release == 0 ? entry : entry.substring(entry.indexOf('/', 18) + 1);
        return path.substring(0, path.length() - 6);
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
