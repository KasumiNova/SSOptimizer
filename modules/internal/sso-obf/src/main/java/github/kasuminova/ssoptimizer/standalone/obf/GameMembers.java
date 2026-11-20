package github.kasuminova.ssoptimizer.standalone.obf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import org.objectweb.asm.*;

final class GameMembers {
    private record Definition(String parent, List<String> interfaces, Set<String> members, Set<String> publicMembers) {}
    private static final Definition ABSENT = new Definition(null, List.of(), Set.of(), Set.of());
    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();
    private final List<Path> archives;

    GameMembers() { this(List.of()); }
    GameMembers(List<Path> archives) { this.archives = List.copyOf(archives); }

    void index(ClassReader reader) {
        definitions.computeIfAbsent(reader.getClassName(), ignored -> declaration(reader));
    }

    private Definition lookup(String owner) {
        for (var archive : archives) {
            try (var jar = new JarFile(archive.toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
                var entry = jar.getJarEntry(owner + ".class");
                if (entry == null) continue;
                try (var input = jar.getInputStream(entry)) {
                    var reader = new ClassReader(input.readAllBytes());
                    if (!owner.equals(reader.getClassName())) throw new IOException("声明类名不符 " + reader.getClassName());
                    return declaration(reader);
                }
            } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new UncheckedIOException(new IOException("读取游戏声明失败 " + archive + "!/" + owner + ".class", e));
            }
        }
        return ABSENT;
    }

    private static Definition declaration(ClassReader reader) {
        Set<String> members = new HashSet<>(), publicMembers = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            void add(String name, boolean method, int flags) {
                if (!ObfClasses.invalid(name, method)) return;
                String key = (method ? "M" : "F") + name;
                members.add(key);
                if ((flags & Opcodes.ACC_PUBLIC) != 0) publicMembers.add(key);
            }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                add(name, false, access);
                return null;
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                add(name, true, access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Definition(reader.getSuperName(), List.of(reader.getInterfaces()), Set.copyOf(members), Set.copyOf(publicMembers));
    }

    boolean has(String owner, String raw, boolean method, boolean declared, boolean publicOnly) {
        String key = (method ? "M" : "F") + raw;
        var pending = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            String current = pending.remove();
            if (!visited.add(current)) continue;
            var definition = definitions.computeIfAbsent(current, this::lookup);
            if ((publicOnly ? definition.publicMembers : definition.members).contains(key)) return true;
            if (declared) continue;
            if (definition.parent != null) pending.add(definition.parent);
            pending.addAll(definition.interfaces);
        }
        return false;
    }
}
