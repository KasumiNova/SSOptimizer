package github.kasuminova.ssoptimizer.standalone.remap;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class MappingProjection {
    private final MemoryMappingTree tree;
    private final Map<String, String> classes;
    private final Set<String> namedOnly;

    private MappingProjection(final MemoryMappingTree tree, final Map<String, String> classes,
                              final Set<String> namedOnly) {
        this.tree = tree;
        this.classes = Map.copyOf(classes);
        this.namedOnly = Set.copyOf(namedOnly);
    }

    static MappingProjection read(final Path mapping) throws IOException {
        final MemoryMappingTree tree = new MemoryMappingTree();
        try (var raw = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(mapping))) {
            raw.mark(2);
            final boolean compressed = raw.read() == 0x1f && raw.read() == 0x8b;
            raw.reset();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    compressed ? new java.util.zip.GZIPInputStream(raw) : raw, java.nio.charset.StandardCharsets.UTF_8))) {
                reader.mark(65536);
                final String header = reader.readLine();
                if (header == null || !header.startsWith("tiny\t2\t0\t")) {
                    throw new PreparationException.Input("Expected Tiny v2 header: " + mapping);
                }
                reader.reset();
                MappingReader.read(reader, new ForwardingMappingVisitor(tree) {
                    private final Set<String> seenClasses = new HashSet<>();
                    private final Set<MemberKey> seenMembers = new HashSet<>();
                    private String owner;

                    @Override
                    public boolean visitClass(final String name) throws IOException {
                        if (!seenClasses.add(name)) {
                            throw new PreparationException.Conflict("Repeated class mapping in " + mapping + ": " + name);
                        }
                        owner = name;
                        return super.visitClass(name);
                    }

                    @Override
                    public boolean visitField(final String name, final String descriptor) throws IOException {
                        unique("field", name, descriptor);
                        return super.visitField(name, descriptor);
                    }

                    @Override
                    public boolean visitMethod(final String name, final String descriptor) throws IOException {
                        unique("method", name, descriptor);
                        return super.visitMethod(name, descriptor);
                    }

                    private void unique(final String kind, final String name, final String descriptor) throws IOException {
                        if (descriptor == null || !seenMembers.add(new MemberKey(owner, kind, name, descriptor))) {
                            throw new PreparationException.Conflict("Repeated or incomplete " + kind + " mapping in " + mapping
                                    + ": " + owner + "." + name + descriptor);
                        }
                    }
                });
            }
        }
        if (!"obf".equals(tree.getSrcNamespace()) || !tree.getDstNamespaces().contains("named")) {
            throw new PreparationException.Input("Expected Tiny obf -> named projection: " + mapping);
        }
        final int named = tree.getNamespaceId("named");
        final Map<String, String> classes = new HashMap<>();
        final Map<String, String> destinations = new HashMap<>();
        for (var entry : tree.getClasses()) {
            final String source = entry.getSrcName();
            final String target = entry.getDstName(named) == null ? source : entry.getDstName(named);
            validateClassName(source, mapping);
            validateClassName(target, mapping);
            final String previous = destinations.putIfAbsent(target, source);
            if (previous != null && !previous.equals(source)) {
                throw new PreparationException.Conflict("Class mapping collision: " + previous + " and " + source + " -> " + target);
            }
            classes.put(source, target);
        }
        if (classes.isEmpty()) {
            throw new PreparationException.Input("Mapping contains no classes: " + mapping);
        }
        final Set<String> namedOnly = new HashSet<>(destinations.keySet());
        namedOnly.removeAll(classes.keySet());
        return new MappingProjection(tree, classes, namedOnly);
    }

    static String digest(final Path mapping) throws IOException {
        try (var raw = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(mapping))) {
            raw.mark(2);
            final boolean compressed = raw.read() == 0x1f && raw.read() == 0x8b;
            raw.reset();
            try (var decoded = compressed ? new java.util.zip.GZIPInputStream(raw) : raw) {
                final var digest = Digests.sha256();
                final byte[] buffer = new byte[65536];
                int count;
                while ((count = decoded.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
                return java.util.HexFormat.of().formatHex(digest.digest());
            }
        }
    }

    IMappingProvider provider() {
        return TinyUtils.createMappingProvider(tree, "obf", "named");
    }

    String mapClass(final String name) {
        return classes.getOrDefault(name, name);
    }

    boolean isNamedOnly(final String name) {
        return namedOnly.contains(name);
    }

    private record MemberKey(String owner, String kind, String name, String descriptor) {
    }

    private static void validateClassName(final String name, final Path mapping) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.endsWith("/") || name.contains("//")
                || name.indexOf('.') >= 0 || name.indexOf(';') >= 0 || name.indexOf('[') >= 0) {
            throw new PreparationException.Input("Invalid class name in " + mapping + ": " + name);
        }
    }
}
