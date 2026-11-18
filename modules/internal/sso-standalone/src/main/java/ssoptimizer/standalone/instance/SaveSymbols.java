package ssoptimizer.standalone.instance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

final class SaveSymbols {
    private record Member(String owner, String name, String descriptor) {}
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> originals = new HashMap<>();
    private final Map<Member, String> methods = new HashMap<>();
    private final Map<Member, String> fields = new HashMap<>();
    private final Map<Member, String> originalFields = new HashMap<>();
    private final java.util.Set<String> changedNames = new java.util.HashSet<>();
    private SaveFields layout = new SaveFields();

    SaveFields layout() { return layout; }
    void bindGame(java.util.List<Path> gameJars) throws IOException { layout = SaveFields.read(gameJars, this); }

    int installedNamespace(String owner) {
        if (classes.containsKey(owner) && !originals.containsKey(owner)) return -1;
        if (originals.containsKey(owner) && !classes.containsKey(owner)) return 1;
        return 0;
    }

    String sourceDescriptor(String descriptor) {
        int l = descriptor.indexOf('L');
        if (l < 0 || !descriptor.endsWith(";")) return descriptor;
        String type = descriptor.substring(l + 1, descriptor.length() - 1);
        return descriptor.substring(0, l + 1) + originals.getOrDefault(type, type) + ';';
    }

    String sourceField(String owner, String name, String sourceDescriptor) {
        return originalFields.getOrDefault(new Member(owner, name, sourceDescriptor), name);
    }
    boolean changedField(String name) { return changedNames.contains(name); }

    String field(String owner, String name, String descriptor) { return fields.getOrDefault(new Member(owner, name, descriptor), name); }
    String original(String type) { return classes.containsKey(type) ? type : originals.getOrDefault(type, type); }

    static SaveSymbols read(Path file) throws IOException {
        try (var in = Files.newInputStream(file);
             var text = new BufferedReader(new InputStreamReader(file.getFileName().toString().endsWith(".gz")
                     ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8))) {
            if (!"tiny\t2\t0\tobf\tnamed".equals(text.readLine())) throw new IOException("需要 Tiny v2 obf -> named 映射：" + file);
            var result = new SaveSymbols();
            String owner = null;
            boolean escaped = false;
            for (String line; (line = text.readLine()) != null;) {
                if (line.equals("\tescaped-names")) escaped = true;
                else if (line.startsWith("c\t") || line.startsWith("\tm\t") || line.startsWith("\tf\t")) {
                    String[] cols = line.split("\t", -1);
                    if (escaped) for (int i = 1; i < cols.length; i++) cols[i] = unescape(cols[i]);
                    if (cols[0].equals("c") && cols.length == 3) {
                        owner = cols[1];
                        String target = cols[2].isEmpty() ? owner : cols[2];
                        if (owner.isEmpty() || result.classes.putIfAbsent(owner, target) != null
                                || result.originals.putIfAbsent(target, owner) != null) throw new IOException("类型映射冲突：" + line);
                    } else if (cols.length == 5 && owner != null && (cols[1].equals("m") || cols[1].equals("f"))) {
                        String target = cols[4].isEmpty() ? cols[3] : cols[4];
                        var members = cols[1].equals("m") ? result.methods : result.fields;
                        if (members == result.fields) {
                            if (!target.equals(cols[3])) result.changedNames.add(encodeNode(target));
                            if (result.originalFields.putIfAbsent(new Member(owner, target, cols[2]), cols[3]) != null) {
                                throw new IOException("字段反向映射存在歧义：" + line);
                            }
                        }
                        if (members.putIfAbsent(new Member(owner, cols[3], cols[2]), target) != null) {
                            throw new IOException("成员映射冲突：" + line);
                        }
                    } else throw new IOException("映射条目格式错误：" + line);
                }
            }
            if (result.classes.isEmpty()) throw new IOException("类型映射为空：" + file);
            return result;
        }
    }

    private static String unescape(String s) throws IOException {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\') out.append(ch);
            else {
                if (++i == s.length()) throw new IOException("截断的 Tiny 转义：" + s);
                out.append(switch (s.charAt(i)) {
                    case 't' -> '\t'; case 'n' -> '\n'; case 'r' -> '\r'; case '0' -> '\0'; case '\\' -> '\\';
                    default -> throw new IOException("未知 Tiny 转义：" + s);
                });
            }
        }
        return out.toString();
    }

    String type(String text) throws IOException {
        String alias = layout.alias(text);
        if (alias != null) {
            String source = text.replace('.', '/');
            if (classes.containsKey(source) && !alias.equals(source)) throw new IOException("游戏类型名与 XML 别名指向不同类：" + text + " / " + alias);
            return text;
        }
        if (text.startsWith("[")) {
            int base = 0;
            while (base < text.length() && text.charAt(base) == '[') base++;
            if (base == text.length()) throw new IOException("数组类型缺少元素：" + text);
            if (text.charAt(base) != 'L') {
                if (text.length() != base + 1 || "ZBCSIFJD".indexOf(text.charAt(base)) < 0) throw new IOException("数组类型无效：" + text);
                return text;
            }
            if (!text.endsWith(";") || text.length() <= base + 2) throw new IOException("对象数组类型无效：" + text);
            return text.substring(0, base + 1) + name(text.substring(base + 1, text.length() - 1)) + ';';
        }
        int end = text.length();
        while (end >= 2 && text.startsWith("[]", end - 2)) end -= 2;
        while (end >= 6 && text.startsWith("-array", end - 6)) end -= 6;
        return end == text.length() ? name(text) : name(text.substring(0, end)) + text.substring(end);
    }

    private String name(String name) throws IOException {
        boolean dotted = name.indexOf('/') < 0;
        String internal = dotted ? name.replace('.', '/') : name;
        String mapped = classes.get(internal);
        if (mapped == null || mapped.equals(internal)) return name;
        if (originals.containsKey(internal)) throw new IOException("源和目标命名空间存在歧义：" + name);
        return dotted ? mapped.replace('/', '.') : mapped;
    }

    String element(String encoded) throws IOException {
        if (encoded.indexOf('.') < 0) return encoded;
        String name = decodeNode(encoded);
        String mapped = type(name);
        return mapped.equals(name) ? encoded : encodeNode(mapped);
    }

    static String encodeNode(String name) { return name.replace("_", "__").replace("$", "_-"); }

    static String decodeNode(String encoded) {
        if (encoded.indexOf('_') < 0) return encoded;
        StringBuilder decoded = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '_' && i + 1 < encoded.length()) {
                if (encoded.charAt(i + 1) == '-') { decoded.append('$'); i++; continue; }
                if (encoded.charAt(i + 1) == '_') { decoded.append('_'); i++; continue; }
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    void lambda(Map<String, String> header) throws IOException {
        for (String key : new String[]{"capturingClass", "functionalInterfaceClass", "functionalInterfaceMethodName",
                "functionalInterfaceMethodSignature", "implClass", "implMethodName", "implMethodSignature", "implMethodKind", "instantiatedMethodType"}) {
            if (!header.containsKey(key)) throw new IOException("SerializedLambda 缺少 " + key);
        }
        String kind = header.get("implMethodKind");
        if (kind.length() != 1 || kind.charAt(0) < '5' || kind.charAt(0) > '9') throw new IOException("SerializedLambda 方法句柄类型无效：" + kind);
        for (String prefix : new String[]{"functionalInterface", "impl"}) {
            String owner = header.get(prefix + "Class");
            String method = header.get(prefix + "MethodName");
            String descriptor = header.get(prefix + "MethodSignature");
            header.put(prefix + "MethodName", methods.getOrDefault(new Member(owner, method, descriptor), method));
            header.put(prefix + "MethodSignature", methodDescriptor(descriptor));
            header.put(prefix + "Class", name(owner));
        }
        header.put("capturingClass", type(header.get("capturingClass")));
        header.put("instantiatedMethodType", methodDescriptor(header.get("instantiatedMethodType")));
    }

    String methodDescriptor(String descriptor) throws IOException {
        if (!descriptor.startsWith("(")) throw new IOException("JVM 方法描述符无效：" + descriptor);
        StringBuilder mapped = new StringBuilder("(");
        int i = 1;
        while (i < descriptor.length() && descriptor.charAt(i) != ')') i = descriptorType(descriptor, i, mapped, false);
        if (i >= descriptor.length()) throw new IOException("方法描述符缺少返回值：" + descriptor);
        mapped.append(')');
        if (descriptorType(descriptor, i + 1, mapped, true) != descriptor.length()) throw new IOException("方法描述符有多余字符：" + descriptor);
        return mapped.toString();
    }

    private int descriptorType(String text, int start, StringBuilder out, boolean returns) throws IOException {
        int i = start;
        while (i < text.length() && text.charAt(i) == '[') { i++; out.append('['); }
        if (i < text.length()) {
            char code = text.charAt(i++);
            if ("ZBCSIFJD".indexOf(code) >= 0 || code == 'V' && returns && i == start + 1) { out.append(code); return i; }
            if (code == 'L') {
                int end = text.indexOf(';', i);
                if (end > i) {
                    String internal = text.substring(i, end);
                    if (internal.indexOf('.') < 0 && internal.indexOf('[') < 0) {
                        out.append('L').append(name(internal)).append(';');
                        return end + 1;
                    }
                }
            }
        }
        throw new IOException("无效 JVM 类型描述符：" + text);
    }
}
