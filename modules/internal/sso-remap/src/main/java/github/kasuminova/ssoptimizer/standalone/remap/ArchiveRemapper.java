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

    static void remap(Path mappingFile, List<Source> sources, List<Path> hierarchyPaths,
                      List<Path> namedPaths, List<Path> order, Path output) throws IOException {
        final MappingProjection mapping = MappingProjection.read(mappingFile);
        var byPath = new HashMap<Path, Source>();
        for (Source source : sources) {
            byPath.put(source.original(), source);
        }
        var hierarchy = new ClassHierarchy();
        var owners = new HashMap<String, Path>();
        for (Path path : hierarchyPaths) {
            ArchiveEntries.readClasses(path, (entry, bytes) -> {
                if (!ModuleMetadata.isDescriptor(entry)) {
                    owners.putIfAbsent(ArchiveEntries.className(entry), path);
                    hierarchy.add(bytes, ArchiveEntries.release(entry), path.toString());
                }
            });
        }
        var namedHeaders = new TreeMap<String,byte[]>();
        var descriptors = new HashMap<Source,Map<String,byte[]>>();
        var originalEntries = new HashMap<Source,Set<String>>();
        var classInputs = new LinkedHashMap<Source,Path>();
        for (Path path : order) {
            Source source = byPath.get(path);
            if (source == null) {
                ArchiveEntries.readClasses(path, (entry, bytes) -> {
                    if (ModuleMetadata.isDescriptor(entry)) return;
                    Path owner = owners.computeIfAbsent(ArchiveEntries.className(entry), ignored -> path);
                    if (!owner.equals(path)) return;
                    hierarchy.add(bytes, ArchiveEntries.release(entry), path.toString());
                    namedHeaders.putIfAbsent(entry, NamedHierarchy.header(bytes));
                });
                continue;
            }
            var modules = new TreeMap<String,byte[]>();
            Set<String> entries = new HashSet<>();
            Path classes = output.getParent().resolve("classes-" + source.outputName());
            try (var filtered = new ZipOutputStream(Files.newOutputStream(classes))) {
                ArchiveEntries.readClasses(path, (entry, bytes) -> {
                    if (ModuleMetadata.isDescriptor(entry)) {
                        modules.put(entry, bytes);
                        return;
                    }
                    String target = mapping.mapClass(ArchiveEntries.className(entry));
                    if (!owners.computeIfAbsent(target, ignored -> path).equals(path) || !entries.add(entry)) return;
                    filtered.putNextEntry(new ZipEntry(entry));
                    filtered.write(bytes);
                    filtered.closeEntry();
                });
            }
            descriptors.put(source, modules);
            originalEntries.put(source, entries);
            classInputs.put(source, classes);
        }
        final TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(mapping.provider())
                .ignoreConflicts(false)
                .ignoreFieldDesc(false)
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .removeFrames(false)
                .fixPackageAccess(true)
                .threads(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())))
                .build();
        final Map<Source, Map<String, byte[]>> transformed = new LinkedHashMap<>();
        try {
            final List<Path> analysisPaths = new ArrayList<>();
            if (!namedPaths.isEmpty()) {
                analysisPaths.add(NamedHierarchy.project(mapping, namedHeaders, hierarchyPaths, output.getParent().resolve("named-hierarchy.jar")));
            }
            analysisPaths.addAll(hierarchyPaths);
            remapper.readClassPath(analysisPaths.toArray(Path[]::new));
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
                    classes.put(entry, bytes);
                }, tags.get(source));
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
                    boolean mod = source.mod();
                    repaired.put(entry.getKey(), BytecodeRepair.rewrite(entry.getValue(), hierarchy, ArchiveEntries.release(entry.getKey()), mapping, mod));
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
            write(source, output.resolve(source.outputName()), repaired, mapping);
        }
    }

    private static void write(final Source source, final Path output, final Map<String, byte[]> classes,
                              final MappingProjection mapping) throws IOException {
        final Set<String> written = new HashSet<>();
        try (ZipFile input = new ZipFile(source.original().toFile());
             ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
            boolean multi = ArchiveEntries.multiRelease(input);
            final ZipEntry originalManifest = ArchiveEntries.manifestEntry(input);
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
                if (name.endsWith(".class") && (ArchiveEntries.visible(name, multi) || ModuleMetadata.isDescriptor(name)) || ArchiveEntries.isManifest(name)
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

    record Source(Path original, boolean mod, String outputName) {}
}
