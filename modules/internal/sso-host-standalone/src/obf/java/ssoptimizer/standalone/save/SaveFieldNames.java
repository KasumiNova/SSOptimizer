package ssoptimizer.standalone.save;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import github.kasuminova.ssoptimizer.standalone.obf.ObfClasses;

public final class SaveFieldNames {
    private static final Names EMPTY = new Names(Map.of(), Map.of());
    private record Names(Map<String, String> original, Map<String, String> physical) {}
    private static final ClassValue<Names> DECLARED = new ClassValue<>() {
        @Override protected Names computeValue(Class<?> owner) {
            var source = owner.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return EMPTY;
            var url = source.getLocation();
            try {
                var location = url.getProtocol().equals("jar")
                        ? ((java.net.JarURLConnection) url.openConnection()).getJarFileURL() : url;
                if (!location.getProtocol().equals("file")) return EMPTY;
                Path archive = Path.of(location.toURI());
                if (!Files.isRegularFile(archive)) return EMPTY;
                var entryName = owner.getName().replace('.', '/') + ".class";
                try (var jar = new JarFile(archive.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    var entry = jar.getJarEntry(entryName);
                    if (entry == null) return EMPTY;
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

    private static Names parse(byte[] bytes) {
        var model = ClassFile.of().parse(bytes);
        var originals = new HashMap<String, String>();
        var physical = new HashMap<String, String>();
        for (var field : model.fields()) {
            if ((field.flags().flagsMask() & (ClassFile.ACC_STATIC | ClassFile.ACC_TRANSIENT)) != 0) continue;
            String raw = field.fieldName().stringValue();
            if (!requiresEncoding(raw)) continue;
            String name = ObfClasses.memberName(raw, false);
            originals.put(name, raw);
            physical.put(raw, name);
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
