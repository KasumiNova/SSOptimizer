package github.kasuminova.ssoptimizer.reobf;

import github.kasuminova.ssoptimizer.standalone.obf.ObfClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

final class GameLinkage {
    record Member(String name, String desc) {
        @Override public String toString() { return name + (desc.startsWith("(") ? "" : ":") + desc; }
    }

    record Declaration(Header owner, int access) { }

    static final class Header {
        final String name;
        final String parent;
        final List<String> interfaces;
        final int access;
        final String nestHost;
        final List<String> nestMembers;
        final List<String> permitted;
        final Path origin;
        final boolean game;
        final Map<Member, Integer> fields = new LinkedHashMap<>();
        final Map<Member, Integer> methods = new LinkedHashMap<>();

        Header(ClassNode node, Path origin, boolean game) {
            name = node.name;
            parent = node.superName;
            interfaces = List.copyOf(node.interfaces);
            access = node.access;
            nestHost = node.nestHostClass == null ? name : node.nestHostClass;
            nestMembers = node.nestMembers == null ? List.of() : List.copyOf(node.nestMembers);
            permitted = node.permittedSubclasses == null ? List.of() : List.copyOf(node.permittedSubclasses);
            this.origin = origin;
            this.game = game;
            for (var field : node.fields) {
                declare(fields, new Member(game ? ObfClasses.memberName(field.name, false) : field.name, field.desc), field.access);
            }
            for (var method : node.methods) {
                declare(methods, new Member(game ? ObfClasses.memberName(method.name, true) : method.name, method.desc), method.access);
            }
        }

        private void declare(Map<Member, Integer> table, Member member, int flags) {
            if (table.putIfAbsent(member, flags) != null) {
                throw new IllegalArgumentException("重复的成员声明: " + name + "." + member);
            }
        }

        boolean isInterface() { return (access & ACC_INTERFACE) != 0; }
    }

    private final Map<String, Header> types;
    private final Set<String> absent = new HashSet<>();
    private final Map<Header, Set<Header>> ancestry = new HashMap<>();

    GameLinkage(Map<String, Header> types) {
        this.types = new HashMap<>(types);
    }

    static Header header(ClassReader reader, Path origin, boolean game) {
        var node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Header(node, origin, game);
    }

    static boolean gameNamespace(String name) {
        return name.startsWith("com/fs/") || name.startsWith("zzz/com/fs/") || name.startsWith("sound/");
    }

    Header find(String name) {
        if (name == null || absent.contains(name)) return null;
        Header found = types.get(name);
        if (found != null) return found;
        try (var in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) {
                absent.add(name);
                return null;
            }
            found = header(new ClassReader(in), null, false);
            types.put(name, found);
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取平台类 " + name, e);
        }
    }

    Set<Header> ancestry(Header type) {
        return ancestry.computeIfAbsent(type, start -> {
            var visited = new LinkedHashSet<Header>();
            var pending = new ArrayDeque<Header>();
            pending.add(start);
            while (!pending.isEmpty()) {
                Header next = pending.remove();
                if (!visited.add(next)) continue;
                Header parent = find(next.parent);
                if (parent != null) pending.add(parent);
                for (String iface : next.interfaces) {
                    Header base = find(iface);
                    if (base != null) pending.add(base);
                }
            }
            return visited;
        });
    }

    boolean subtype(Header child, Header parent) { return ancestry(child).contains(parent); }

    boolean gameRelated(Header type) { return ancestry(type).stream().anyMatch(parent -> parent.game); }

    static boolean samePackage(Header first, Header second) {
        int end = first.name.lastIndexOf('/');
        return end == second.name.lastIndexOf('/') && first.name.regionMatches(0, second.name, 0, end + 1);
    }

    boolean nestmates(Header first, Header second) {
        if (first == second) return true;
        if (!first.nestHost.equals(second.nestHost) || !samePackage(first, second)) return false;
        Header host = find(first.nestHost);
        return host != null && host.nestHost.equals(host.name) && samePackage(first, host)
                && (first == host || host.nestMembers.contains(first.name))
                && (second == host || host.nestMembers.contains(second.name));
    }

    Declaration field(Header owner, Member member) { return field(owner, member, new HashSet<>()); }

    private Declaration field(Header owner, Member member, Set<Header> visited) {
        if (owner == null || !visited.add(owner)) return null;
        Integer flags = owner.fields.get(member);
        if (flags != null) return new Declaration(owner, flags);
        for (String iface : owner.interfaces) {
            Declaration inherited = field(find(iface), member, visited);
            if (inherited != null) return inherited;
        }
        return owner.isInterface() ? null : field(find(owner.parent), member, visited);
    }

    Declaration method(Header owner, Member member) {
        Integer flags = owner.methods.get(member);
        if (flags != null) return new Declaration(owner, flags);
        if (member.name.startsWith("<")) return null;
        if (owner.isInterface()) {
            Header object = find("java/lang/Object");
            flags = object.methods.get(member);
            if (flags != null && (flags & (ACC_PUBLIC | ACC_STATIC)) == ACC_PUBLIC) return new Declaration(object, flags);
        } else {
            var visited = new HashSet<Header>();
            for (Header base = find(owner.parent); base != null && visited.add(base); base = find(base.parent)) {
                flags = base.methods.get(member);
                if (flags != null) return new Declaration(base, flags);
            }
        }
        var candidates = new LinkedHashMap<Header, Integer>();
        for (Header base : ancestry(owner)) {
            if (!base.isInterface()) continue;
            flags = base.methods.get(member);
            if (flags != null && (flags & (ACC_STATIC | ACC_PRIVATE)) == 0) candidates.put(base, flags);
        }
        var maximal = candidates.keySet().stream().filter(base -> candidates.keySet().stream()
                .noneMatch(other -> other != base && subtype(other, base))).toList();
        var defaults = maximal.stream().filter(base -> (candidates.get(base) & ACC_ABSTRACT) == 0).toList();
        if (defaults.size() > 1) throw new IllegalArgumentException("接口默认方法冲突: " + owner.name + "." + member);
        if (maximal.isEmpty()) return null;
        Header selected = defaults.isEmpty() ? maximal.getFirst() : defaults.getFirst();
        return new Declaration(selected, candidates.get(selected));
    }
}
