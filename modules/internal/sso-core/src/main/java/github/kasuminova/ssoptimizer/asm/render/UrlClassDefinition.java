package github.kasuminova.ssoptimizer.asm.render;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class UrlClassDefinition {
    private static final Logger LOGGER = Logger.getLogger(UrlClassDefinition.class);
    private static final MethodType DEFINE_CLASS = MethodType.methodType(Class.class,
            String.class, byte[].class, int.class, int.class, CodeSource.class);
    private static final MethodType DEFINE_PACKAGE = MethodType.methodType(Package.class,
            String.class, Manifest.class, URL.class);
    private static final MethodType DEFINE_UNSEALED_PACKAGE = MethodType.methodType(Package.class,
            String.class, String.class, String.class, String.class, String.class, String.class, String.class, URL.class);
    private static final ClassValue<LookupPolicy> LOOKUP_POLICY = new ClassValue<>() {
        @Override
        protected LookupPolicy computeValue(final Class<?> receiver) {
            Class<?> nearestGenerated = null;
            try {
                for (Class<?> type = receiver; type != URLClassLoader.class; type = type.getSuperclass()) {
                    for (var method : type.getDeclaredMethods()) {
                        if ((method.getName().equals("findClass") || method.getName().equals("findResource"))
                                && method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
                            if (method.getName().equals("findClass") && method.isSynthetic()
                                    && ClassDefinitionRedirector.generatedLookup(type)) {
                                if (nearestGenerated == null) nearestGenerated = type;
                            } else {
                                return new LookupPolicy(false, null);
                            }
                        }
                    }
                }
                return new LookupPolicy(true, nearestGenerated);
            } catch (LinkageError | SecurityException failure) {
                LOGGER.debug("无法验证 URL 类加载器的查找方法", failure);
                return new LookupPolicy(false, null);
            }
        }
    };

    private static volatile BiFunction<URL, byte[], byte[]> classNormalizer;

    private UrlClassDefinition() { }

    public static synchronized void installClassNormalizer(final BiFunction<URL, byte[], byte[]> normalizer) {
        if (classNormalizer != null) throw new IllegalStateException("类净化器已注册");
        classNormalizer = Objects.requireNonNull(normalizer);
    }

    public static boolean hasClassNormalizer() {
        return classNormalizer != null;
    }

    static byte[] normalizeFromArchive(final CodeSource source, final String name, final byte[] bytes) {
        final BiFunction<URL, byte[], byte[]> normalizer = classNormalizer;
        if (normalizer == null || source == null || name == null || name.isEmpty() || name.indexOf('/') >= 0
                || source.getCodeSigners() != null || source.getCertificates() != null) return bytes;
        final URL location = source.getLocation();
        if (location == null || !location.getProtocol().equals("file")) return bytes;
        final String authority = location.getAuthority();
        if (authority != null && !authority.isEmpty()) return bytes;
        final String entryName = name.replace('.', '/') + ".class";
        try (var jar = new JarFile(Path.of(location.toURI()).toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
            final var entry = jar.getJarEntry(entryName);
            if (entry == null || entry.isDirectory() || entry.getSize() != bytes.length) return bytes;
            try (var in = jar.getInputStream(entry)) {
                if (!Arrays.equals(bytes, in.readNBytes(bytes.length)) || in.read() != -1 || entry.getCodeSigners() != null) {
                    return bytes;
                }
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException | SecurityException ex) {
            LOGGER.debug("无法验证显式定义的归档来源 " + name, ex);
            return bytes;
        }
        return Objects.requireNonNull(normalizer.apply(location, bytes));
    }

    /** 返回 null 时，由生成的调用点执行原 findClass。 */
    public static Class<?> findClass(final URLClassLoader loader, final String name,
                                     final MethodHandles.Lookup caller, final boolean inherited) throws Throwable {
        if (loader == null || name == null) return null;
        final Class<?> owner = caller.lookupClass();
        final LookupPolicy policy = LOOKUP_POLICY.get(loader.getClass());
        if (!policy.standard() || inherited && policy.nearestGenerated() != owner) return null;
        final JarClass candidate = readClass(loader, name);
        if (candidate == null) return null;
        final BiFunction<URL, byte[], byte[]> normalizer = classNormalizer;
        final byte[] normalized = normalizer == null ? candidate.bytes()
                : Objects.requireNonNull(normalizer.apply(candidate.location(), candidate.bytes()));
        final byte[] bytes = ClassDefinitionRedirector.redirect(loader, name, normalized);
        if (bytes == candidate.bytes()) return null;
        final MethodHandle defineClass = caller.findVirtual(owner, "defineClass", DEFINE_CLASS);
        ensurePackage(loader, name, candidate, caller, owner);
        return (Class<?>) defineClass.invoke(loader, name, bytes, 0, bytes.length,
                new CodeSource(candidate.location(), (CodeSigner[]) null));
    }

    private static JarClass readClass(final URLClassLoader loader, final String name) {
        final URL resource = loader.findResource(name.replace('.', '/') + ".class");
        if (resource == null || !resource.getProtocol().equals("jar")) return null;
        try {
            if (!(resource.openConnection() instanceof JarURLConnection connection)) return null;
            final URL location = connection.getJarFileURL();
            final String authority = location.getAuthority();
            if (!location.getProtocol().equals("file") || (authority != null && !authority.isEmpty())) return null;
            connection.setUseCaches(false);
            try (var jar = connection.getJarFile()) {
                // URLClassPath 将实际 MR 条目写入资源 URL。按该 URL 的条目名读取。
                final var entry = jar.getJarEntry(connection.getEntryName());
                if (entry == null || entry.isDirectory()) return null;
                try (var input = jar.getInputStream(entry)) {
                    final byte[] data = input.readAllBytes();
                    if (entry.getCodeSigners() != null) return null;
                    return new JarClass(location, jar.getManifest(), data);
                }
            }
        } catch (IOException failure) {
            LOGGER.debug("无法读取待重定向的归档类 " + name, failure);
            return null;
        }
    }

    private static void ensurePackage(final URLClassLoader loader, final String className, final JarClass source,
                                      final MethodHandles.Lookup caller, final Class<?> owner) throws Throwable {
        final int end = className.lastIndexOf('.');
        if (end < 0) return;
        final String name = className.substring(0, end);
        if (verifyPackage(loader, name, source)) return;
        try {
            if (source.manifest() != null) {
                caller.findVirtual(owner, "definePackage", DEFINE_PACKAGE)
                        .invoke(loader, name, source.manifest(), source.location());
            } else {
                caller.findVirtual(owner, "definePackage", DEFINE_UNSEALED_PACKAGE)
                        .invoke(loader, name, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException alreadyDefined) {
            if (!verifyPackage(loader, name, source)) throw alreadyDefined;
        }
    }

    private static boolean verifyPackage(final URLClassLoader loader, final String name, final JarClass source) {
        final Package pkg = loader.getDefinedPackage(name);
        if (pkg == null) return false;
        if (pkg.isSealed()) {
            if (!pkg.isSealed(source.location())) throw new SecurityException("sealing violation: package " + name + " is sealed");
        } else if (isSealed(name, source.manifest())) {
            throw new SecurityException("sealing violation: can't seal package " + name + ": already loaded");
        }
        return true;
    }

    private static boolean isSealed(final String name, final Manifest manifest) {
        if (manifest == null) return false;
        final Attributes attributes = manifest.getAttributes(name.replace('.', '/') + "/");
        final String perPackage = attributes == null ? null : attributes.getValue(Attributes.Name.SEALED);
        final String sealed = perPackage == null ? manifest.getMainAttributes().getValue(Attributes.Name.SEALED) : perPackage;
        return "true".equalsIgnoreCase(sealed);
    }

    private record LookupPolicy(boolean standard, Class<?> nearestGenerated) { }

    private record JarClass(URL location, Manifest manifest, byte[] bytes) { }
}
