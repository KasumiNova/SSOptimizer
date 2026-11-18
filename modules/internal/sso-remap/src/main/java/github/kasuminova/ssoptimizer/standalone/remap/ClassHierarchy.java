package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

final class ClassHierarchy {
    private final Map<String, NavigableMap<Integer, Header>> headers = new HashMap<>();

    void add(final byte[] bytes, final int release, final String origin) {
        final ClassReader reader = new ClassReader(bytes);
        final Header header = new Header(reader.getClassName(), reader.getSuperName(), List.of(reader.getInterfaces()),
                (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0, origin);
        final var versions = headers.computeIfAbsent(header.name(), ignored -> new TreeMap<>());
        final Header previous = versions.putIfAbsent(release, header);
        if (previous != null && (!java.util.Objects.equals(previous.parent(), header.parent())
                || !previous.interfaces().equals(header.interfaces()) || previous.isInterface() != header.isInterface())) {
            throw new IllegalArgumentException("Conflicting hierarchy for " + header.name() + ": " + previous.origin() + " and " + origin);
        }
    }

    String commonType(final String left, final String right, final int release) {
        if (isAssignable(left, right, release)) {
            return left;
        }
        if (isAssignable(right, left, release)) {
            return right;
        }
        if (left.startsWith("[") && right.startsWith("[")) {
            final String l = component(left);
            final String r = component(right);
            if (!primitive(l) && !primitive(r)) {
                final String common = commonType(l, r, release);
                return "[" + (common.startsWith("[") ? common : "L" + common + ";");
            }
            return "java/lang/Object";
        }
        if (left.startsWith("[") || right.startsWith("[")
                || header(left, release).isInterface() || header(right, release).isInterface()) {
            return "java/lang/Object";
        }
        String current = left;
        final Set<String> visited = new HashSet<>();
        while (current != null) {
            if (!visited.add(current)) {
                throw new IllegalArgumentException("Cyclic class hierarchy: " + left + " via " + current);
            }
            current = header(current, release).parent();
            if (current != null && isAssignable(current, right, release)) {
                return current;
            }
        }
        throw new IllegalArgumentException("No common superclass for " + left + " and " + right);
    }

    boolean isAssignable(final String target, final String actual, final int release) {
        if (target.equals(actual) || target.equals("java/lang/Object")) {
            return true;
        }
        if (actual.startsWith("[")) {
            if (target.equals("java/lang/Cloneable") || target.equals("java/io/Serializable")) {
                return true;
            }
            if (!target.startsWith("[")) {
                return false;
            }
            final String t = component(target);
            final String a = component(actual);
            return primitive(t) || primitive(a) ? t.equals(a) : isAssignable(t, a, release);
        }
        if (target.startsWith("[")) {
            return false;
        }
        final ArrayDeque<String> pending = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        pending.add(actual);
        while (!pending.isEmpty()) {
            final String name = pending.removeFirst();
            if (!visited.add(name)) {
                continue;
            }
            if (target.equals(name)) {
                return true;
            }
            final Header h = header(name, release);
            if (h.parent() != null) {
                pending.add(h.parent());
            }
            pending.addAll(h.interfaces());
        }
        return false;
    }

    private Header header(final String name, final int release) {
        NavigableMap<Integer, Header> versions = headers.get(name);
        if (versions == null) {
            try (InputStream stream = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                if (stream == null) {
                    throw new IllegalArgumentException("Missing hierarchy class " + name + "; include its defining archive in hierarchyJars");
                }
                add(stream.readAllBytes(), 0, "JDK " + Runtime.version());
                versions = headers.get(name);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read JDK hierarchy class " + name, error);
            }
        }
        final var entry = versions.floorEntry(Math.max(release, Runtime.version().feature()));
        if (entry == null) {
            throw new IllegalArgumentException("No applicable multi-release definition for " + name + " at " + release);
        }
        return entry.getValue();
    }

    private static String component(final String array) {
        final String component = array.substring(1);
        return component.startsWith("L") ? component.substring(1, component.length() - 1) : component;
    }

    private static boolean primitive(final String type) {
        return type.length() == 1 && "ZCBSIFJD".indexOf(type.charAt(0)) >= 0;
    }

    private record Header(String name, String parent, List<String> interfaces, boolean isInterface, String origin) {
    }
}
