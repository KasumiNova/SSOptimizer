package github.kasuminova.ssoptimizer.standalone.remap;

import net.fabricmc.tinyremapper.TinyRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class NamedHierarchy {
    private NamedHierarchy() {
    }

    static Path project(final MappingProjection mapping, final List<Path> named, final List<Path> dependencies,
                        final Path output) throws IOException {
        final Map<String, byte[]> headers = new TreeMap<>();
        for (Path path : named) {
            ArchiveEntries.validate(path, false, (entry, bytes) -> {
                if (ModuleMetadata.isDescriptor(entry)) {
                    return;
                }
                final ClassWriter writer = new ClassWriter(0);
                new ClassReader(bytes).accept(writer, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                final byte[] header = writer.toByteArray();
                final byte[] previous = headers.putIfAbsent(entry, header);
                if (previous != null && !java.util.Arrays.equals(previous, header)) {
                    throw new IllegalArgumentException("Conflicting named hierarchy declarations: " + path + "!" + entry);
                }
            });
        }
        final Path input = output.resolveSibling("named-hierarchy-input.jar");
        write(input, headers);
        final TinyRemapper reverse = TinyRemapper.newRemapper().withMappings(mapping.reverseProvider())
                .ignoreConflicts(false).removeFrames(true).threads(1).build();
        final Map<String, byte[]> projected = new TreeMap<>();
        try {
            reverse.readClassPath(dependencies.toArray(Path[]::new));
            reverse.readInputs(input);
            reverse.apply((name, bytes) -> {
                final String entry = (name.startsWith("/META-INF/versions/") ? name.substring(1) : name) + ".class";
                if (projected.putIfAbsent(entry, bytes) != null) {
                    throw new IllegalArgumentException("Conflicting projected hierarchy entry: " + entry);
                }
            });
        } finally {
            reverse.finish();
        }
        write(output, projected);
        return output;
    }

    private static void write(final Path output, final Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output, java.nio.file.StandardOpenOption.CREATE_NEW))) {
            for (var entry : entries.entrySet()) {
                ArchiveEntries.validateName(entry.getKey());
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
}
