package ssoptimizer.standalone.instance;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

final class SaveFields {
    private static final String FACTORY = "com/fs/starfarer/campaign/save/CampaignGameManager";
    private static final Set<String> CONTAINERS = Set.of("java/util/Collection", "java/util/List", "java/util/Set", "java/util/Map",
            "java/util/Map$Entry", "java/util/ArrayList", "java/util/HashMap", "java/util/LinkedHashMap", "java/util/HashSet",
            "java/util/LinkedHashSet", "java/util/EnumSet", "java/util/EnumMap", "java/util/TreeSet", "java/util/TreeMap",
            "java/util/SortedSet", "java/util/SortedMap", "java/util/Vector", "java/util/LinkedList", "java/util/Hashtable");
    private record Shape(String parent, Map<String, List<String>> fields) {}
    record Field(String xml, String type) {}
    private final Map<String, Shape> types = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, Map<String, String>> fieldAliases = new HashMap<>();

    static SaveFields read(List<Path> jars, SaveSymbols symbols) throws IOException {
        SaveFields result = new SaveFields();
        boolean named = false, obfuscated = false;
        for (Path path : jars) {
            try (JarFile jar = new JarFile(path.toFile())) {
                for (var it = jar.entries(); it.hasMoreElements();) {
                    var entry = it.nextElement();
                    if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("com/fs/")) continue;
                    try (var in = jar.getInputStream(entry)) {
                        var model = ClassFile.of().parse(in.readAllBytes());
                        String name = model.thisClass().asInternalName();
                        int namespace = symbols.installedNamespace(name);
                        named |= namespace > 0;
                        obfuscated |= namespace < 0;
                        if (named && obfuscated) throw sourceRequired("类归档混合使用 obf 和 named 声明");
                        if (result.types.containsKey(name)) continue;
                        Map<String, List<String>> fields = new HashMap<>();
                        for (var f : model.fields()) {
                            if ((f.flags().flagsMask() & (ClassFile.ACC_STATIC | ClassFile.ACC_TRANSIENT)) != 0) continue;
                            fields.computeIfAbsent(f.fieldName().stringValue(), unused -> new ArrayList<>()).add(f.fieldType().stringValue());
                        }
                        result.types.put(name, new Shape(model.superclass().map(c -> c.asInternalName()).orElse(null), fields));
                        if (name.equals(FACTORY)) result.readAliases(model);
                    }
                }
            } catch (IllegalArgumentException e) { throw new IOException("无法解析 RC8 的存档类定义：" + path, e); }
        }
        if (!result.types.containsKey(FACTORY) || !result.aliases.containsKey("CampaignEngine")) throw new IOException("RC8 游戏归档缺少战役存档工厂或其类型别名");
        return named ? result.sourceLayout(symbols) : result;
    }

    private static IOException sourceRequired(String reason) {
        return new IOException(reason + "。请用写入旧档时的原版 RC8 安装作为 --game，指定新的临时 --instance 执行 import-save；"
                + "成功后将转换好的整个槽目录复制到 named 实例的 saves 下，选择未被占用的槽名。");
    }

    private SaveFields sourceLayout(SaveSymbols symbols) throws IOException {
        SaveFields result = new SaveFields();
        for (var type : types.entrySet()) {
            Shape installed = type.getValue();
            String owner = symbols.original(type.getKey());
            var restored = new HashMap<String, List<String>>();
            for (var member : installed.fields.entrySet()) {
                for (String descriptor : member.getValue()) {
                    String originalType = symbols.sourceDescriptor(descriptor);
                    String originalName = symbols.sourceField(owner, member.getKey(), originalType);
                    var descriptors = restored.computeIfAbsent(originalName, unused -> new ArrayList<>());
                    if (descriptors.contains(originalType)) throw sourceRequired("无法唯一还原字段 " + owner + '/' + originalName);
                    descriptors.add(originalType);
                }
            }
            result.types.put(owner, new Shape(installed.parent == null ? null : symbols.original(installed.parent), restored));
        }
        aliases.forEach((alias, target) -> result.aliases.put(alias, target.startsWith("[")
                ? symbols.sourceDescriptor(target) : symbols.original(target)));
        for (var row : fieldAliases.entrySet()) {
            String owner = symbols.original(row.getKey());
            for (var alias : row.getValue().entrySet()) {
                String name = alias.getValue();
                String recovered = null;
                for (String cls = row.getKey(); cls != null && types.containsKey(cls); cls = types.get(cls).parent) {
                    var descriptors = types.get(cls).fields.get(name);
                    if (descriptors == null) continue;
                    if (descriptors.size() != 1) throw sourceRequired("无法唯一还原别名 " + row.getKey() + '/' + alias.getKey());
                    recovered = symbols.sourceField(symbols.original(cls), name, symbols.sourceDescriptor(descriptors.getFirst()));
                    break;
                }
                result.fieldAlias(owner, alias.getKey(), recovered == null ? name : recovered);
            }
        }
        return result;
    }

    private void readAliases(ClassModel model) throws IOException {
        for (var method : model.methods()) {
            if (!method.methodType().stringValue().equals("(Ljava/lang/String;)Lcom/thoughtworks/xstream/XStream;") || method.code().isEmpty()) continue;
            Object a = null, b = null, c = null;
            for (var element : method.code().get()) {
                if (!(element instanceof Instruction instruction)) continue;
                if (instruction instanceof InvokeInstruction call && call.owner().asInternalName().equals("com/thoughtworks/xstream/XStream")) {
                    String name = call.name().stringValue();
                    if (name.equals("alias") && b instanceof String alias && c instanceof ClassDesc type) {
                        String prev = aliases.putIfAbsent(alias, descriptorType(type.descriptorString()));
                        if (prev != null && !prev.equals(descriptorType(type.descriptorString()))) throw new IOException("RC8 类型别名冲突：" + alias);
                    } else if (name.equals("aliasAttribute") && a instanceof ClassDesc owner && b instanceof String field && c instanceof String alias) {
                        fieldAlias(descriptorType(owner.descriptorString()), alias, field);
                    } else if (name.equals("aliasField") && a instanceof String alias && b instanceof ClassDesc owner && c instanceof String field) {
                        fieldAlias(descriptorType(owner.descriptorString()), alias, field);
                    }
                }
                a = b; b = c; c = instruction instanceof ConstantInstruction constant ? constant.constantValue() : null;
            }
        }
    }

    private void fieldAlias(String owner, String alias, String name) throws IOException {
        String prior = fieldAliases.computeIfAbsent(owner, unused -> new HashMap<>()).putIfAbsent(alias, name);
        if (prior != null && !prior.equals(name)) throw new IOException("RC8 字段别名冲突：" + owner + '/' + alias);
    }

    private static String descriptorType(String desc) {
        return desc.startsWith("L") && desc.endsWith(";") ? desc.substring(1, desc.length() - 1) : desc;
    }

    String alias(String token) { return aliases.get(token); }

    String resolveType(String token, SaveSymbols symbols) {
        if (token == null) return null;
        String alias = aliases.get(token);
        if (alias != null) return alias;
        String source = symbols.original(token.replace('.', '/'));
        return types.containsKey(source) || CONTAINERS.contains(source) || source.startsWith("[") ? source : null;
    }

    boolean container(String type) { return type != null && (type.startsWith("[") || CONTAINERS.contains(type)); }

    Field field(String owner, String xml, String definedIn, SaveSymbols symbols) throws IOException {
        if (owner == null) return null;
        if (definedIn != null) {
            String declared = resolveType(definedIn, symbols);
            if (declared == null) return null;
            boolean found = false;
            for (String cls = owner; cls != null && types.containsKey(cls); cls = types.get(cls).parent) if (cls.equals(declared)) { found = true; break; }
            if (!found) throw new IOException("字段声明类型与对象不一致：" + owner + '/' + xml + " defined-in=" + definedIn);
            owner = declared;
        }
        String token = SaveSymbols.decodeNode(xml);
        String raw = token;
        boolean aliased = false;
        for (String cls = owner; cls != null && types.containsKey(cls); cls = types.get(cls).parent) {
            var alias = fieldAliases.getOrDefault(cls, Map.of()).get(token);
            if (alias != null) { raw = alias; aliased = true; break; }
        }
        for (String cls = owner; cls != null && types.containsKey(cls); cls = types.get(cls).parent) {
            List<String> candidates = types.get(cls).fields.get(raw);
            if (candidates == null) continue;
            if (candidates.size() != 1) throw new IOException("同一存档字段有多个 JVM 描述符：" + cls + '/' + raw + " " + candidates);
            String descriptor = candidates.getFirst();
            String named = symbols.field(cls, raw, descriptor);
            return new Field(aliased || named.equals(raw) ? xml : SaveSymbols.encodeNode(named), descriptorType(descriptor));
        }
        return null;
    }
}
