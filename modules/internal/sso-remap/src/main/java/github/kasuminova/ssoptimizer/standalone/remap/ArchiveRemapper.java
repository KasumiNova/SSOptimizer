package github.kasuminova.ssoptimizer.standalone.remap;

import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.TinyRemapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class ArchiveRemapper {
    private ArchiveRemapper() {
    }

    static void remap(final Path mappingFile, final List<Source> sources, final List<Path> hierarchyPaths,
                      final Path output, final String mappingDigest) throws IOException {
        final MappingProjection mapping = MappingProjection.read(mappingFile);
        final Map<String, Definition> definitions = new HashMap<>();
        final Map<Source, Set<String>> expected = new LinkedHashMap<>();
        final Map<Source, Set<String>> originalEntries = new LinkedHashMap<>();
        final Map<Source, Map<String, byte[]>> descriptors = new LinkedHashMap<>();
        final Map<Source, Path> classInputs = new LinkedHashMap<>();
        for (Source source : sources) {
            final Map<String, byte[]> modules = new TreeMap<>();
            final Set<String> original = ArchiveEntries.validate(source.snapshot(), mapping, true, (entry, bytes) -> {
                if (ModuleMetadata.isDescriptor(entry)) {
                    modules.put(entry, bytes);
                    return;
                }
                final Definition next = new Definition(Digests.bytes(bytes), source.original() + "!" + entry);
                final Definition previous = definitions.putIfAbsent(entry, next);
                if (previous != null && !previous.hash().equals(next.hash())) {
                    throw new IllegalArgumentException("Conflicting class definitions: " + previous.origin() + " and " + next.origin());
                }
            });
            final Set<String> targets = new HashSet<>();
            for (String entry : original) {
                if (ModuleMetadata.isDescriptor(entry)) {
                    continue;
                }
                if (!targets.add(ArchiveEntries.mappedClassEntry(entry, mapping))) {
                    throw new PreparationException.Conflict("Mapped class entry collision in " + source.original() + ": " + entry);
                }
            }
            expected.put(source, targets);
            descriptors.put(source, modules);
            originalEntries.put(source, original);
            final Path classes = source.snapshot().resolveSibling(source.snapshot().getFileName() + "-classes.jar");
            try (ZipFile input = new ZipFile(source.snapshot().toFile());
                 ZipOutputStream filtered = new ZipOutputStream(Files.newOutputStream(classes, java.nio.file.StandardOpenOption.CREATE_NEW))) {
                for (String entry : original.stream().sorted().toList()) {
                    if (ModuleMetadata.isDescriptor(entry)) {
                        continue;
                    }
                    filtered.putNextEntry(new ZipEntry(entry));
                    try (var stream = input.getInputStream(input.getEntry(entry))) {
                        stream.transferTo(filtered);
                    }
                    filtered.closeEntry();
                }
            }
            classInputs.put(source, classes);
        }
        final ClassHierarchy hierarchy = new ClassHierarchy();
        for (Path path : hierarchyPaths) {
            ArchiveEntries.validate(path, mapping, false,
                    (entry, bytes) -> {
                        if (!ModuleMetadata.isDescriptor(entry)) {
                            hierarchy.add(bytes, ArchiveEntries.release(entry), path + "!" + entry);
                        }
                    });
        }
        final TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(mapping.provider())
                .ignoreConflicts(false)
                .ignoreFieldDesc(false)
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .removeFrames(true)
                .fixPackageAccess(true)
                .threads(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())))
                .build();
        final Map<Source, Map<String, byte[]>> transformed = new LinkedHashMap<>();
        try {
            remapper.readClassPath(hierarchyPaths.toArray(Path[]::new));
            final Map<Source, InputTag> tags = new LinkedHashMap<>();
            for (Source source : sources) {
                final InputTag tag = remapper.createInputTag();
                tags.put(source, tag);
                remapper.readInputs(tag, classInputs.get(source));
            }
            for (Source source : sources) {
                final Map<String, byte[]> classes = new TreeMap<>();
                remapper.apply((name, bytes) -> {
                    final String entry = (name.startsWith("/META-INF/versions/") ? name.substring(1) : name) + ".class";
                    if (classes.putIfAbsent(entry, bytes) != null) {
                        throw new IllegalArgumentException("Duplicate transformed class: " + entry + " from " + source.original());
                    }
                }, tags.get(source));
                if (!classes.keySet().equals(expected.get(source))) {
                    final Set<String> missing = new HashSet<>(expected.get(source));
                    missing.removeAll(classes.keySet());
                    final Set<String> unexpected = new HashSet<>(classes.keySet());
                    unexpected.removeAll(expected.get(source));
                    throw new PreparationException.Conflict("Remapped archive entry mismatch for " + source.original()
                            + ": missing=" + missing + ", unexpected=" + unexpected);
                }
                transformed.put(source, classes);
                classes.forEach((entry, bytes) -> hierarchy.add(bytes, ArchiveEntries.release(entry), source.original() + "!" + entry));
            }
        } catch (RuntimeException error) {
            throw new PreparationException.Conflict("Remapping failed for " + sources.stream().map(Source::original).toList()
                    + ": " + error.getMessage(), error);
        } finally {
            remapper.finish();
        }
        for (Source source : sources) {
            final Map<String, byte[]> repaired = new TreeMap<>();
            for (var entry : transformed.get(source).entrySet()) {
                try {
                    repaired.put(entry.getKey(), BytecodeRepair.rewrite(entry.getValue(), hierarchy, ArchiveEntries.release(entry.getKey()), mapping));
                } catch (RuntimeException error) {
                    throw new PreparationException.Conflict("Bytecode repair failed for " + source.original() + "!" + entry.getKey()
                            + ": " + error.getMessage(), error);
                }
            }
            for (var descriptor : descriptors.get(source).entrySet()) {
                try {
                    repaired.put(descriptor.getKey(), ModuleMetadata.rewrite(descriptor.getValue(), originalEntries.get(source), mapping));
                } catch (RuntimeException error) {
                    throw new PreparationException.Conflict("Module metadata rewrite failed for " + source.original()
                            + "!" + descriptor.getKey() + ": " + error.getMessage(), error);
                }
            }
            write(source, output.resolve(source.outputName()), repaired, mapping, mappingDigest);
        }
    }

    private static void write(final Source source, final Path output, final Map<String, byte[]> classes,
                              final MappingProjection mapping, final String mappingDigest) throws IOException {
        final Set<String> written = new HashSet<>();
        try (ZipFile input = new ZipFile(source.snapshot().toFile());
             ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
            final ZipEntry originalManifest = input.getEntry("META-INF/MANIFEST.MF");
            final Manifest manifest;
            if (originalManifest == null) {
                manifest = new Manifest();
            } else {
                try (var stream = input.getInputStream(originalManifest)) {
                    manifest = new Manifest(stream);
                }
            }
            final Attributes attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            ArchiveEntries.stripDigests(attributes);
            manifest.getEntries().values().forEach(ArchiveEntries::stripDigests);
            for (String name : List.of("Main-Class", "Premain-Class", "Agent-Class", "Launcher-Agent-Class")) {
                final String value = attributes.getValue(name);
                if (value != null) {
                    attributes.putValue(name, mapping.mapClass(value.replace('.', '/')).replace('/', '.'));
                }
            }
            attributes.putValue(ArchiveEntries.NAMESPACE, "named");
            attributes.putValue("SSOptimizer-Remap-ABI", ArchivePreparation.REMAP_ABI);
            attributes.putValue("SSOptimizer-Mapping-SHA256", mappingDigest);
            attributes.putValue("SSOptimizer-Origin-SHA256", source.digest());
            attributes.putValue("SSOptimizer-Origin-URI", source.original().toUri().toASCIIString());
            final ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            manifest.write(manifestBytes);
            put(target, written, "META-INF/MANIFEST.MF", manifestBytes.toByteArray(), source.original());
            for (var entry : classes.entrySet()) {
                put(target, written, entry.getKey(), entry.getValue(), source.original());
            }
            final var entries = input.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") || name.equals("META-INF/MANIFEST.MF")
                        || ArchiveEntries.invalidSignature(name)) {
                    continue;
                }
                byte[] bytes;
                try (var stream = input.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (name.startsWith("META-INF/services/")) {
                    name = "META-INF/services/" + mapping.mapClass(name.substring(18).replace('.', '/')).replace('/', '.');
                    bytes = serviceProviders(bytes, mapping);
                }
                put(target, written, name, bytes, source.original());
            }
            final String metadata = "namespace=named\nabi=" + ArchivePreparation.REMAP_ABI
                    + "\nmapping=" + mappingDigest + "\nsource.sha256=" + source.digest()
                    + "\nsource.uri=" + source.original().toUri().toASCIIString() + "\nrole=" + source.role() + "\n";
            put(target, written, ArchiveEntries.METADATA, metadata.getBytes(StandardCharsets.UTF_8), source.original());
        }
    }

    static byte[] serviceProviders(final byte[] bytes, final MappingProjection mapping) {
        final String text = new String(bytes, StandardCharsets.UTF_8);
        final StringBuilder output = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            final int comment = line.indexOf('#');
            final String provider = (comment < 0 ? line : line.substring(0, comment)).trim();
            if (provider.isEmpty()) {
                output.append(line);
            } else {
                output.append(mapping.mapClass(provider.replace('.', '/')).replace('/', '.'));
                if (comment >= 0) {
                    output.append(' ').append(line.substring(comment));
                }
            }
            output.append('\n');
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void put(final ZipOutputStream stream, final Set<String> written, final String name,
                            final byte[] bytes, final Path origin) throws IOException {
        ArchiveEntries.validateName(name);
        for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
            final String directory = name.substring(0, slash + 1);
            if (written.contains(directory.substring(0, directory.length() - 1))) {
                throw new PreparationException.Conflict("Output file/directory collision in " + origin + ": " + directory);
            }
            if (written.add(directory)) {
                final ZipEntry directoryEntry = new ZipEntry(directory);
                directoryEntry.setTime(0);
                stream.putNextEntry(directoryEntry);
                stream.closeEntry();
            }
        }
        if (name.endsWith("/")) {
            return;
        }
        if (written.contains(name + "/") || !written.add(name)) {
            throw new PreparationException.Conflict("Output resource collision in " + origin + ": " + name);
        }
        final ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        stream.putNextEntry(entry);
        stream.write(bytes);
        stream.closeEntry();
    }

    record Source(Path original, Path snapshot, String role, String digest, String outputName) {
    }

    private record Definition(String hash, String origin) {
    }
}
