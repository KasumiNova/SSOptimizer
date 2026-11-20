package github.kasuminova.ssoptimizer.reobf;

import github.kasuminova.ssoptimizer.standalone.obf.ObfClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

final class GameClasses {
    private record Signature(String name, String descriptor) {}
    private record Definition(int access, String superName, List<String> interfaces,
                              Map<Signature, Integer> fields, Map<Signature, Integer> methods) {}

    private final Map<String, Definition> classes;

    private GameClasses(Map<String, Definition> classes) {
        this.classes = Map.copyOf(classes);
    }

    static GameClasses read(List<Path> archives) throws IOException {
        Map<String, Definition> definitions = new HashMap<>();
        for (Path archive : archives) {
            try (var zip = new ZipFile(archive.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                    var type = new ClassNode();
                    try (var stream = zip.getInputStream(entry)) {
                        new ClassReader(stream).accept(type, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                    Map<Signature, Integer> fields = new HashMap<>();
                    for (FieldNode field : type.fields) fields.put(new Signature(ObfClasses.memberName(field.name, false), field.desc), field.access);
                    Map<Signature, Integer> methods = new HashMap<>();
                    for (MethodNode method : type.methods) methods.put(new Signature(ObfClasses.memberName(method.name, true), method.desc), method.access);
                    var definition = new Definition(type.access, type.superName, List.copyOf(type.interfaces), Map.copyOf(fields), Map.copyOf(methods));
                    Definition previous = definitions.putIfAbsent(type.name, definition);
                    if (previous != null && !previous.equals(definition)) {
                        throw new IOException("Conflicting game class " + type.name + " in " + archive);
                    }
                }
            }
        }
        if (definitions.isEmpty()) throw new IOException("The original game classpath contains no classes: " + archives);
        return new GameClasses(definitions);
    }

    boolean contains(String name) {
        return classes.containsKey(name);
    }

    boolean declares(String owner, String name, String descriptor, boolean method) {
        Definition type = classes.get(owner);
        return type != null && (method ? type.methods() : type.fields()).keySet().stream().anyMatch(signature ->
                signature.name().equals(name) && (descriptor == null || signature.descriptor().equals(descriptor)));
    }

    boolean hasMember(String owner, String name, String descriptor, boolean method) {
        return hasMember(owner, name, descriptor, method, new HashSet<>());
    }

    private boolean hasMember(String owner, String name, String descriptor, boolean method, Set<String> seen) {
        if (!seen.add(owner)) return false;
        if (declares(owner, name, descriptor, method)) return true;
        Definition type = classes.get(owner);
        if (type == null || method && name.startsWith("<")) return false;
        if (type.superName() != null && hasMember(type.superName(), name, descriptor, method, seen)) return true;
        for (String parent : type.interfaces()) {
            if (hasMember(parent, name, descriptor, method, seen)) return true;
        }
        return false;
    }
}
