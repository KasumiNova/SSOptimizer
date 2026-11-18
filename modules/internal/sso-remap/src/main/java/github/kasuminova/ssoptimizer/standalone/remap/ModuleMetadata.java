package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.ModuleExportNode;
import org.objectweb.asm.tree.ModuleOpenNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ModuleMetadata {
    private ModuleMetadata() {
    }

    static boolean isDescriptor(final String entry) {
        return entry.endsWith(".class") && ArchiveEntries.className(entry).equals("module-info");
    }

    static byte[] rewrite(final byte[] original, final Set<String> archiveClasses, final MappingProjection mapping) {
        final Map<String, Set<String>> packages = new LinkedHashMap<>();
        for (String entry : archiveClasses) {
            if (isDescriptor(entry)) {
                continue;
            }
            final String source = ArchiveEntries.className(entry);
            final String target = mapping.mapClass(source);
            final int sourceSlash = source.lastIndexOf('/');
            final int targetSlash = target.lastIndexOf('/');
            if (sourceSlash >= 0 && targetSlash >= 0) {
                packages.computeIfAbsent(source.substring(0, sourceSlash), ignored -> new LinkedHashSet<>())
                        .add(target.substring(0, targetSlash));
            }
        }
        final boolean[] changed = {false};
        final ClassNode node = new ClassNode();
        new ClassReader(original).accept(new ClassRemapper(node, new Remapper(Opcodes.ASM9) {
            @Override
            public String map(final String name) {
                final String mapped = mapping.mapClass(name);
                changed[0] |= !mapped.equals(name);
                return mapped;
            }
        }), 0);
        if (node.module == null) {
            throw new IllegalArgumentException("module-info.class does not contain a Module attribute");
        }
        if (node.module.packages != null) {
            final Set<String> mapped = new LinkedHashSet<>();
            for (String name : node.module.packages) {
                mapped.addAll(packages.getOrDefault(name, Set.of(name)));
            }
            changed[0] |= !mapped.equals(new LinkedHashSet<>(node.module.packages));
            node.module.packages = new ArrayList<>(mapped);
        }
        if (node.module.exports != null) {
            final Map<String, ModuleExportNode> exports = new LinkedHashMap<>();
            for (var export : node.module.exports) {
                for (String target : packages.getOrDefault(export.packaze, Set.of(export.packaze))) {
                    changed[0] |= !target.equals(export.packaze);
                    final var prior = exports.putIfAbsent(target, new ModuleExportNode(target, export.access, export.modules));
                    if (prior != null && (prior.access != export.access || !Objects.equals(prior.modules, export.modules))) {
                        throw new IllegalArgumentException("Mapped module export collision: " + target);
                    }
                }
            }
            node.module.exports = new ArrayList<>(exports.values());
        }
        if (node.module.opens != null) {
            final Map<String, ModuleOpenNode> opens = new LinkedHashMap<>();
            for (var open : node.module.opens) {
                for (String target : packages.getOrDefault(open.packaze, Set.of(open.packaze))) {
                    changed[0] |= !target.equals(open.packaze);
                    final var prior = opens.putIfAbsent(target, new ModuleOpenNode(target, open.access, open.modules));
                    if (prior != null && (prior.access != open.access || !Objects.equals(prior.modules, open.modules))) {
                        throw new IllegalArgumentException("Mapped module open-package collision: " + target);
                    }
                }
            }
            node.module.opens = new ArrayList<>(opens.values());
        }
        if (!changed[0]) {
            return original;
        }
        if (node.attrs != null && !node.attrs.isEmpty()) {
            throw new IllegalArgumentException("Cannot rewrite opaque module attributes: " + node.attrs.stream().map(attribute -> attribute.type).toList());
        }
        final ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
