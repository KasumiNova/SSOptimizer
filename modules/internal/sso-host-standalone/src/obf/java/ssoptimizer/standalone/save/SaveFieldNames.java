package ssoptimizer.standalone.save;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

public final class SaveFieldNames {
    private static final String ORIGINAL = "Lgithub/kasuminova/ssoptimizer/standalone/obf/OriginalField;";
    private static final Names EMPTY = new Names(Map.of(), Map.of());
    private record Names(Map<String, String> original, Map<String, String> physical) {}
    private static final Map<Path, Path> ARCHIVES = archives();
    private static final ClassValue<Names> DECLARED = new ClassValue<>() {
        @Override protected Names computeValue(Class<?> owner) {
            var source = owner.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return EMPTY;
            var url = source.getLocation();
            try {
                var location = url.getProtocol().equals("jar")
                        ? ((java.net.JarURLConnection) url.openConnection()).getJarFileURL() : url;
                if (!location.getProtocol().equals("file")) return EMPTY;
                var archive = ARCHIVES.get(Path.of(location.toURI()).toAbsolutePath().normalize());
                if (archive == null) return EMPTY;
                var entryName = owner.getName().replace('.', '/') + ".class";
                try (var jar = new JarFile(archive.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    var entry = jar.getJarEntry(entryName);
                    if (entry == null) throw new IOException("定义归档缺少字段所属类：" + archive + " / " + entryName);
                    try (var in = jar.getInputStream(entry)) { return parse(in.readAllBytes()); }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("读取存档字段声明失败：" + owner.getName() + " @ " + url, e);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("存档字段所属归档地址无效：" + url, e);
            }
        }
    };

    private SaveFieldNames() {}

    private static Map<Path, Path> archives() {
        var plan = Path.of(System.getProperty("ssoptimizer.standalone.plan"));
        try (var reader = Files.newBufferedReader(plan)) {
            var values = new Properties();
            values.load(reader);
            var result = new HashMap<Path, Path>();
            for (String group : new String[]{"game", "mod"}) {
                int count = Integer.parseInt(values.getProperty(group + ".count"));
                for (int i = 0; i < count; i++) {
                    Path prepared = Path.of(values.getProperty(group + "." + i)).toRealPath();
                    Path original = Path.of(values.getProperty(group + ".source." + i)).toRealPath();
                    for (Path key : new Path[]{original, prepared}) {
                        var old = result.putIfAbsent(key, prepared);
                        if (old != null && !old.equals(prepared)) throw new IOException("存档字段定义域冲突：" + key);
                    }
                }
            }
            return Map.copyOf(result);
        } catch (IOException e) { throw new UncheckedIOException("无法读取游戏存档归档来源：" + plan, e); }
    }

    private static Names parse(byte[] bytes) throws IOException {
        var model = ClassFile.of().parse(bytes);
        var originals = new HashMap<String, String>();
        var physical = new HashMap<String, String>();
        for (var field : model.fields()) {
            if ((field.flags().flagsMask() & (ClassFile.ACC_STATIC | ClassFile.ACC_TRANSIENT)) != 0) continue;
            var annotations = field.findAttribute(Attributes.runtimeInvisibleAnnotations());
            if (annotations.isEmpty()) continue;
            for (var annotation : annotations.get().annotations()) {
                if (!annotation.className().equalsString(ORIGINAL)) continue;
                var pairs = annotation.elements();
                if (pairs.size() != 1 || !pairs.getFirst().name().equalsString("value")
                        || !(pairs.getFirst().value() instanceof AnnotationValue.OfString value)) {
                    throw new IOException("非法存档字段注解：" + model.thisClass().asInternalName() + '/' + field.fieldName());
                }
                String raw = value.constant().stringValue();
                String name = field.fieldName().stringValue();
                if (!requiresEncoding(raw) || !github.kasuminova.ssoptimizer.standalone.obf.ObfClasses.memberName(raw, false).equals(name)) {
                    throw new IOException("存档字段名与原始声明不符：" + model.thisClass().asInternalName() + '/' + name);
                }
                if (originals.putIfAbsent(name, raw) != null || physical.putIfAbsent(raw, name) != null) {
                    throw new IOException("同一声明类中存在重复的存档字段：" + model.thisClass().asInternalName() + '/' + raw);
                }
            }
        }
        return originals.isEmpty() ? EMPTY : new Names(Map.copyOf(originals), Map.copyOf(physical));
    }

    public static boolean requiresEncoding(String name) {
        return name.isEmpty() || name.indexOf('.') >= 0 || name.indexOf(';') >= 0 || name.indexOf('[') >= 0 || name.indexOf('/') >= 0;
    }

    public static String original(Class<?> owner, String physical) {
        for (var type = owner; type != null; type = type.getSuperclass()) {
            var name = DECLARED.get(type).original.get(physical);
            if (name != null) return name;
        }
        return physical;
    }

    public static String physical(Class<?> owner, String original) {
        for (var type = owner; type != null; type = type.getSuperclass()) {
            var name = DECLARED.get(type).physical.get(original);
            if (name != null) return name;
        }
        return original;
    }
}
