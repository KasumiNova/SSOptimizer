package github.kasuminova.ssoptimizer.asm.render;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.jar.Attributes;
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
            boolean standardEnumeration = true;
            try {
                for (Class<?> type = receiver; type != URLClassLoader.class; type = type.getSuperclass()) {
                    for (var method : type.getDeclaredMethods()) {
                        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != String.class) continue;
                        if (method.getName().equals("findResources")) {
                            standardEnumeration = false;
                        } else if (method.getName().equals("findClass") || method.getName().equals("findResource")) {
                            if (method.getName().equals("findClass") && method.isSynthetic()
                                    && ClassDefinitionRedirector.generatedLookup(type)) {
                                if (nearestGenerated == null) nearestGenerated = type;
                            } else {
                                return new LookupPolicy(false, false, null);
                            }
                        }
                    }
                }
                return new LookupPolicy(true, standardEnumeration, nearestGenerated);
            } catch (LinkageError | SecurityException failure) {
                LOGGER.debug("无法验证 URL 类加载器的查找方法", failure);
                return new LookupPolicy(false, false, null);
            }
        }
    };

    private static volatile BiFunction<URL, byte[], byte[]> classNormalizer;
    private static volatile ScopedNormalizer scopedNormalizer;

    private UrlClassDefinition() { }

    public static synchronized void installClassNormalizer(final BiFunction<URL, byte[], byte[]> normalizer) {
        checkNormalizerRegistration();
        classNormalizer = Objects.requireNonNull(normalizer);
    }

    public static synchronized void installClassNormalizer(final Predicate<URL> allowedSource,
                                                           final BiFunction<String, byte[], byte[]> normalizer) {
        checkNormalizerRegistration();
        scopedNormalizer = new ScopedNormalizer(Objects.requireNonNull(allowedSource), Objects.requireNonNull(normalizer));
    }

    private static void checkNormalizerRegistration() {
        if (hasClassNormalizer()) throw new IllegalStateException("类净化器已注册");
    }

    public static boolean hasClassNormalizer() {
        return classNormalizer != null || hasScopedNormalizer();
    }

    static boolean hasScopedNormalizer() {
        return scopedNormalizer != null;
    }

    static byte[] normalizeFromArchive(final CodeSource source, final String name, final byte[] bytes) {
        if (source == null || !validBinaryName(name) || !localFile(source.getLocation())) return bytes;
        final URL location = source.getLocation();
        final ScopedNormalizer scoped = scopedNormalizer;
        if (scoped != null) {
            if (!scoped.allowedSource().test(location)) return bytes;
            final byte[] result = scoped.normalizer().apply(name.replace('.', '/'), bytes);
            if (result != bytes && (source.getCodeSigners() != null || source.getCertificates() != null)) {
                throw new SecurityException("已签名类需要改写 " + location + "!/" + name);
            }
            return result;
        }
        final BiFunction<URL, byte[], byte[]> normalizer = classNormalizer;
        if (normalizer == null || source.getCodeSigners() != null || source.getCertificates() != null) return bytes;
        return normalizer.apply(location, bytes);
    }

    /** 返回 null 时，由生成的调用点执行原 findClass。 */
    public static Class<?> findClass(final URLClassLoader loader, final String name,
                                     final MethodHandles.Lookup caller, final boolean inherited) throws Throwable {
        if (loader == null || name == null) return null;
        final Class<?> owner = caller.lookupClass();
        final LookupPolicy policy = LOOKUP_POLICY.get(loader.getClass());
        if (!policy.standard() || inherited && policy.nearestGenerated() != owner) return null;
        final ScopedNormalizer scoped = scopedNormalizer;
        if (scoped != null) return findManagedClass(loader, name, caller, owner, policy.standardEnumeration(), scoped);
        final JarClass candidate = readClass(loader, name);
        if (candidate == null) return null;
        final BiFunction<URL, byte[], byte[]> normalizer = classNormalizer;
        final byte[] normalized = normalizer == null ? candidate.bytes()
                : Objects.requireNonNull(normalizer.apply(candidate.location(), candidate.bytes()));
        final byte[] bytes = ClassDefinitionRedirector.redirect(loader, name, normalized);
        if (bytes == candidate.bytes()) return null;
        return define(loader, name, candidate, bytes, caller, owner);
    }

    private static Class<?> findManagedClass(final URLClassLoader loader, final String name, final MethodHandles.Lookup caller,
                                            final Class<?> owner, final boolean standardEnumeration, final ScopedNormalizer scoped)
            throws Throwable {
        if (!validBinaryName(name)) return null;
        final String internalName = name.replace('.', '/');
        final String entryName = internalName + ".class";
        final URL first = loader.findResource(entryName);
        if (first == null) return null;
        URL resource = first;
        java.util.Enumeration<URL> remaining = null;
        ClassNotFoundException rejected = null;
        do {
            final JarClass candidate;
            try {
                candidate = readManagedClass(resource, scoped);
            } catch (IOException failure) {
                final var unavailable = new ClassNotFoundException("无法读取 " + name + " 的候选资源 " + resource, failure);
                if (rejected != null) unavailable.addSuppressed(rejected);
                throw unavailable;
            }
            if (candidate == null) {
                if (rejected == null) return redirectUnmanaged(loader, name, first, caller, owner);
                throw new ClassNotFoundException("无法在原定义域验证 " + name + " 的候选资源 " + resource, rejected);
            }
            final byte[] normalized;
            try {
                normalized = repair(scoped, internalName, candidate, resource);
            } catch (IllegalArgumentException invalid) {
                if (rejected == null) rejected = new ClassNotFoundException("无可用的归档类 " + name);
                rejected.addSuppressed(new IOException("类格式无效 " + resource, invalid));
                LOGGER.warn("跳过类格式无效的归档条目 " + resource + " (" + name + "): " + invalid.getMessage());
                if (!standardEnumeration) throw rejected;
                if (remaining == null) remaining = loader.findResources(entryName);
                resource = nextResource(remaining, first);
                continue;
            }
            final byte[] bytes = candidate.signers() == null
                    ? ClassDefinitionRedirector.redirect(loader, name, normalized) : candidate.bytes();
            if (rejected == null && bytes == candidate.bytes()) return null;
            try {
                return define(loader, name, candidate, bytes, caller, owner);
            } catch (SecurityException failure) {
                throw new SecurityException("归档类定义违反签名或包密封约束 " + resource, failure);
            }
        } while (resource != null);
        throw rejected;
    }

    private static URL nextResource(final java.util.Enumeration<URL> resources, final URL first) {
        while (resources.hasMoreElements()) {
            final URL next = resources.nextElement();
            if (!next.toExternalForm().equals(first.toExternalForm())) return next;
        }
        return null;
    }

    private static Class<?> define(final URLClassLoader loader, final String name, final JarClass candidate, final byte[] bytes,
                                   final MethodHandles.Lookup caller, final Class<?> owner) throws Throwable {
        final MethodHandle defineClass = caller.findVirtual(owner, "defineClass", DEFINE_CLASS);
        ensurePackage(loader, name, candidate, caller, owner);
        return (Class<?>) defineClass.invoke(loader, name, bytes, 0, bytes.length,
                new CodeSource(candidate.location(), candidate.signers()));
    }

    private static JarClass readManagedClass(final URL resource, final ScopedNormalizer scoped) throws IOException {
        if (!resource.getProtocol().equals("jar") || !(resource.openConnection() instanceof JarURLConnection connection)) return null;
        final URL location = connection.getJarFileURL();
        if (!localFile(location) || !scoped.allowedSource().test(location)) return null;
        connection.setUseCaches(false);
        try (var jar = connection.getJarFile()) {
            final var entry = jar.getJarEntry(connection.getEntryName());
            if (entry == null || entry.isDirectory()) throw new IOException("归档类条目不可读 " + resource);
            try (var input = jar.getInputStream(entry)) {
                final byte[] bytes = input.readAllBytes();
                return new JarClass(location, jar.getManifest(), bytes, entry.getCodeSigners());
            }
        } catch (SecurityException failure) {
            throw new SecurityException("归档签名验证失败 " + resource, failure);
        }
    }

    private static byte[] repair(final ScopedNormalizer scoped, final String name, final JarClass candidate, final URL resource) {
        final byte[] normalized;
        try {
            normalized = Objects.requireNonNull(scoped.normalizer().apply(name, candidate.bytes()));
        } catch (IllegalArgumentException invalid) {
            if (candidate.signers() == null) throw invalid;
            throw new SecurityException("已签名类的格式无效 " + resource, invalid);
        }
        if (candidate.signers() != null && normalized != candidate.bytes()) {
            throw new SecurityException("已签名类需要改写 " + resource);
        }
        return normalized;
    }

    static boolean redirectExplicit(final CodeSource source) {
        return scopedNormalizer == null || source == null || source.getCodeSigners() == null && source.getCertificates() == null;
    }

    private static boolean localFile(final URL location) {
        return location != null && location.getProtocol().equals("file")
                && (location.getAuthority() == null || location.getAuthority().isEmpty());
    }

    private static boolean validBinaryName(final String name) {
        return name != null && !name.isEmpty() && !name.startsWith("java.") && name.indexOf('/') < 0
                && name.indexOf('[') < 0 && name.indexOf(';') < 0;
    }

    private static Class<?> redirectUnmanaged(final URLClassLoader loader, final String name, final URL resource,
                                               final MethodHandles.Lookup caller, final Class<?> owner) throws Throwable {
        final JarClass candidate = readClass(resource, name);
        if (candidate == null) return null;
        final byte[] bytes = ClassDefinitionRedirector.redirect(loader, name, candidate.bytes());
        if (bytes == candidate.bytes()) return null;
        return define(loader, name, candidate, bytes, caller, owner);
    }

    private static JarClass readClass(final URLClassLoader loader, final String name) {
        return readClass(loader.findResource(name.replace('.', '/') + ".class"), name);
    }

    private static JarClass readClass(final URL resource, final String name) {
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
                    return new JarClass(location, jar.getManifest(), data, null);
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

    private record LookupPolicy(boolean standard, boolean standardEnumeration, Class<?> nearestGenerated) { }

    private record JarClass(URL location, Manifest manifest, byte[] bytes, CodeSigner[] signers) { }

    private record ScopedNormalizer(Predicate<URL> allowedSource, BiFunction<String, byte[], byte[]> normalizer) { }
}
