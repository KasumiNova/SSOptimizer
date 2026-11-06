package github.kasuminova.ssoptimizer.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 经 ServiceLoader 实例化已由指定加载器定义的测试类，测试代码本身不使用反射。
 */
public final class ClassInstances {
    private ClassInstances() {
    }

    public static <T> T create(final Class<T> type, final ClassLoader definingLoader, final String className) {
        final String entryName = "META-INF/services/" + type.getName();
        final ServiceEntryLoader loader = new ServiceEntryLoader(definingLoader, entryName, writeServiceEntry(className));
        return ServiceLoader.load(type, loader).findFirst()
                .orElseThrow(() -> new IllegalStateException("未能实例化动态定义的类: " + className));
    }

    private static URL writeServiceEntry(final String className) {
        try {
            final Path entry = Files.createTempFile("sso-service-entry-", ".txt");
            entry.toFile().deleteOnExit();
            Files.writeString(entry, className + System.lineSeparator(), StandardCharsets.UTF_8);
            return entry.toUri().toURL();
        } catch (IOException e) {
            throw new UncheckedIOException("写入服务描述文件失败: " + className, e);
        }
    }

    private static final class ServiceEntryLoader extends ClassLoader {
        private final String entryName;
        private final URL   entryUrl;

        ServiceEntryLoader(final ClassLoader parent, final String entryName, final URL entryUrl) {
            super(parent);
            this.entryName = entryName;
            this.entryUrl = entryUrl;
        }

        @Override
        public Enumeration<URL> getResources(final String name) throws IOException {
            if (entryName.equals(name)) {
                return Collections.enumeration(List.of(entryUrl));
            }
            return super.getResources(name);
        }
    }
}
