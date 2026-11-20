package github.kasuminova.ssoptimizer.reobf;

import github.kasuminova.ssoptimizer.standalone.obf.ObfClasses;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.api.TrClass;
import net.fabricmc.tinyremapper.api.TrEnvironment;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

final class ReobfMappings {
    private record Member(String owner, String name, boolean method) {}
    private record Variant(String descriptor, String output) {}

    private final IMappingProvider provider;
    private final Map<String, String> classes;
    private final Map<Member, List<Variant>> members;
    private final Remapper descriptors = new Remapper(Opcodes.ASM9) {
        @Override
        public String map(String name) {
            return className(name);
        }
    };
    private TrEnvironment environment;

    private ReobfMappings(IMappingProvider provider, Map<String, String> classes, Map<Member, List<Variant>> members) {
        this.provider = provider;
        this.classes = Map.copyOf(classes);
        this.members = Map.copyOf(members);
    }

    static ReobfMappings read(Path file) throws IOException {
        var tree = new MemoryMappingTree();
        try (var fileStream = new BufferedInputStream(Files.newInputStream(file))) {
            fileStream.mark(2);
            boolean gzip = fileStream.read() == 0x1f && fileStream.read() == 0x8b;
            fileStream.reset();
            try (var reader = new InputStreamReader(gzip ? new GZIPInputStream(fileStream) : fileStream, StandardCharsets.UTF_8)) {
                MappingReader.read(reader, tree);
            }
        }
        if (!"obf".equals(tree.getSrcNamespace()) || !tree.getDstNamespaces().contains("named")) {
            throw new IOException("Expected a Tiny mapping with obf as source and named as destination: " + file);
        }
        IMappingProvider raw = TinyUtils.createMappingProvider(tree, "named", "obf");
        IMappingProvider provider = acceptor -> raw.load(new IMappingProvider.MappingAcceptor() {
            @Override public void acceptClass(String source, String target) {
                acceptor.acceptClass(source, target);
            }
            @Override public void acceptMethod(IMappingProvider.Member source, String target) {
                acceptor.acceptMethod(source, ObfClasses.memberName(target, true));
            }
            @Override public void acceptField(IMappingProvider.Member source, String target) {
                acceptor.acceptField(source, ObfClasses.memberName(target, false));
            }
            @Override public void acceptMethodArg(IMappingProvider.Member method, int index, String destination) {
                acceptor.acceptMethodArg(method, index, destination);
            }
            @Override public void acceptMethodVar(IMappingProvider.Member method, int index, int start, int row, String destination) {
                acceptor.acceptMethodVar(method, index, start, row, destination);
            }
        });
        var classes = new HashMap<String, String>();
        var members = new HashMap<Member, List<Variant>>();
        provider.load(new IMappingProvider.MappingAcceptor() {
            @Override public void acceptClass(String source, String target) {
                String previous = classes.putIfAbsent(source, target);
                if (previous != null && !previous.equals(target)) {
                    throw new IllegalArgumentException("Ambiguous class mapping for " + source + ": " + previous + ", " + target);
                }
            }
            @Override public void acceptMethod(IMappingProvider.Member source, String target) {
                add(source, target, true);
            }
            @Override public void acceptField(IMappingProvider.Member source, String target) {
                add(source, target, false);
            }
            @Override public void acceptMethodArg(IMappingProvider.Member method, int index, String destination) {}
            @Override public void acceptMethodVar(IMappingProvider.Member method, int index, int start, int row, String destination) {}

            private void add(IMappingProvider.Member source, String target, boolean method) {
                members.computeIfAbsent(new Member(source.owner, source.name, method), ignored -> new ArrayList<>())
                        .add(new Variant(source.desc, target));
            }
        });
        return new ReobfMappings(provider, classes, members);
    }

    void bind(TrEnvironment value) {
        environment = value;
    }

    IMappingProvider provider() {
        return provider;
    }

    String className(String internalName) {
        return classes.getOrDefault(internalName, internalName);
    }

    String descriptor(String desc) {
        return desc.startsWith("(") ? descriptors.mapMethodDesc(desc) : descriptors.mapDesc(desc);
    }

    String fieldName(String namedOwner, String name, String namedDesc) {
        return mappedMemberName(namedOwner, name, namedDesc, false, true);
    }

    String methodName(String namedOwner, String name, String namedDesc) {
        return mappedMemberName(namedOwner, name, namedDesc, true, true);
    }

    String mappedMemberName(String namedOwner, String name, String namedDesc, boolean method, boolean requireUnique) {
        var direct = mappedNames(namedOwner, name, namedDesc, method);
        if (namedDesc != null && direct.isEmpty() && environment != null) {
            var remapper = environment.getRemapper();
            return method ? remapper.mapMethodName(namedOwner, name, namedDesc) : remapper.mapFieldName(namedOwner, name, namedDesc);
        }
        if (direct.isEmpty()) return name;
        if (requireUnique && direct.size() > 1) {
            throw new IllegalArgumentException("Ambiguous mapped member " + namedOwner + '.' + name + ": " + direct);
        }
        return direct.getFirst();
    }

    List<String> mappedNames(String namedOwner, String name, String namedDesc, boolean method) {
        return variants(namedOwner, name, namedDesc, method, new HashSet<>());
    }

    private List<String> variants(String owner, String name, String desc, boolean method, Set<String> visited) {
        if (!visited.add(owner)) return List.of();
        var names = new LinkedHashSet<String>();
        for (var variant : members.getOrDefault(new Member(owner, name, method), List.of())) {
            if (desc == null || desc.equals(variant.descriptor())) names.add(variant.output());
        }
        if (environment != null && names.isEmpty()) {
            TrClass type = environment.getClass(owner);
            if (type != null) {
                String superName = type.getSuperName();
                if (superName != null) names.addAll(variants(superName, name, desc, method, visited));
                for (String implemented : type.getInterfaceNames()) {
                    names.addAll(variants(implemented, name, desc, method, visited));
                }
            }
        }
        return List.copyOf(names);
    }
}
