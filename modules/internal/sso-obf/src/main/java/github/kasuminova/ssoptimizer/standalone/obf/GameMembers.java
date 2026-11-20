package github.kasuminova.ssoptimizer.standalone.obf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;

final class GameMembers {
    private record Definition(String parent, List<String> interfaces, Set<String> members, Set<String> publicMembers) {}
    private final Map<String,Definition> definitions = new HashMap<>();

    void index(Path archive) throws IOException {
        try (var jar = new JarFile(archive.toFile(), false, JarFile.OPEN_READ, Runtime.version())) {
            for (var entry : jar.versionedStream().filter(e -> !e.getName().startsWith("META-INF/") && e.getName().endsWith(".class")).toList()) {
                try (var stream = jar.getInputStream(entry)) { index(new ClassReader(stream)); }
            }
        } catch (RuntimeException e) { throw new IOException("读取游戏声明失败 " + archive, e); }
    }

    void index(ClassReader reader) {
        if (definitions.containsKey(reader.getClassName())) return;
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
        definitions.put(reader.getClassName(), new Definition(reader.getSuperName(), List.of(reader.getInterfaces()), Set.copyOf(members), Set.copyOf(publicMembers)));
    }

    boolean has(String owner, String raw, boolean method, boolean declared, boolean publicOnly) {
        String key = (method ? "M" : "F") + raw;
        var pending = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            String current = pending.remove();
            if (!visited.add(current)) continue;
            var definition = definitions.get(current);
            if (definition == null) continue;
            if ((publicOnly ? definition.publicMembers : definition.members).contains(key)) return true;
            if (declared) continue;
            if (definition.parent != null) pending.add(definition.parent);
            pending.addAll(definition.interfaces);
        }
        return false;
    }
}
