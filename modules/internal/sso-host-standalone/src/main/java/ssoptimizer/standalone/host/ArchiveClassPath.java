package ssoptimizer.standalone.host;

import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Predicate;
import java.util.function.BiFunction;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

final class ArchiveClassPath extends URLStreamHandler {
    private static final System.Logger LOG = System.getLogger(ArchiveClassPath.class.getName());
    private final Roots roots;
    private final URL origin;
    private final URL definition;
    private final String prefix;
    private final boolean game, mod;
    private final URLClassLoader directory;
    private final Set<String> rejected = new HashSet<>();
    private final LinkedHashMap<String, URLConnection> recent = new LinkedHashMap<>(16, .75f, true);
    private JarFile jar;
    private boolean opened;

    static void mount(LaunchClassLoader loader, List<Path> archives, Set<Path> game, Map<Path, Path> originals, Predicate<URL> mod,
                      BiFunction<String, byte[], byte[]> normalizer) throws IOException {
        var roots = new Roots(loader, game, originals, mod, normalizer);
        for (Path path : archives) {
            URL url = path.toUri().toURL();
            roots.pending.addLast(url);
            loader.getSources().add(url);
        }
        roots.next();
    }

    private static final class Roots {
        final LaunchClassLoader loader;
        final Set<Path> game;
        final Map<Path, Path> originals;
        final Map<Path, URL> converted = new HashMap<>();
        final Predicate<URL> mod;
        final BiFunction<String, byte[], byte[]> normalizer;
        final ArrayDeque<URL> pending = new ArrayDeque<>();
        final Set<URL> mounted = new HashSet<>();

        Roots(LaunchClassLoader loader, Set<Path> game, Map<Path, Path> originals, Predicate<URL> mod, BiFunction<String, byte[], byte[]> normalizer) throws IOException {
            this.loader = loader;
            this.game = game;
            this.originals = originals;
            for (var entry : originals.entrySet()) {
                if (!entry.getKey().equals(entry.getValue())) converted.put(entry.getValue(), entry.getKey().toUri().toURL());
            }
            this.mod = mod;
            this.normalizer = normalizer;
        }

        void next() throws IOException {
            while (!pending.isEmpty()) {
                URL url = pending.removeFirst();
                if (!mounted.add(url)) continue;
                ArchiveClassPath view;
                try { view = new ArchiveClassPath(this, url); }
                catch (IOException | IllegalArgumentException invalid) {
                    LOG.log(System.Logger.Level.WARNING, "跳过无效类路径 " + url, invalid);
                    continue;
                }
                loader.addSilentURL(URL.of(URI.create("jar:" + view.prefix), view));
                return;
            }
        }
    }

    private ArchiveClassPath(Roots roots, URL origin) throws IOException {
        this.roots = roots;
        this.origin = origin;
        this.prefix = origin.toExternalForm() + "!/";
        Path path = path(origin);
        definition = roots.originals.getOrDefault(path, path).toUri().toURL();
        directory = origin.getPath().endsWith("/") ? new URLClassLoader(new URL[]{origin}, null) : null;
        game = roots.game.contains(path);
        mod = directory == null && Files.isRegularFile(path) && roots.mod.test(origin);
    }

    private static Path path(URL url) throws IOException {
        try { return Path.of(url.toURI()); }
        catch (URISyntaxException ex) { throw new IOException("无效的本地类路径 " + url, ex); }
    }

    private void open() throws IOException {
        if (opened) return;
        opened = true;
        try {
            if (directory != null || !Files.isRegularFile(path(origin))) return;
            jar = new JarFile(path(origin).toFile(), true, JarFile.OPEN_READ, Runtime.version());
            Manifest manifest = jar.getManifest();
            String classPath = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null) return;
            var dependencies = new ArrayDeque<URL>();
            var tokens = new StringTokenizer(classPath);
            while (tokens.hasMoreTokens()) {
                String name = tokens.nextToken();
                try {
                    @SuppressWarnings("deprecation") URL url = new URL(definition, name);
                    if (!url.getProtocol().equals("file")) continue;
                    if (!roots.converted.isEmpty()) {
                        Path requested = path(url);
                        if (Files.isRegularFile(requested)) url = roots.converted.getOrDefault(requested.toRealPath(), url);
                    }
                    dependencies.addLast(url);
                } catch (IOException | IllegalArgumentException failure) {
                    LOG.log(System.Logger.Level.WARNING, "跳过无效 Class-Path 条目 " + name + " @ " + origin, failure);
                }
            }
            while (!dependencies.isEmpty()) roots.pending.addFirst(dependencies.removeLast());
        } catch (SecurityException failure) {
            throw new LinkageError("无法读取签名归档 " + origin, failure);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "无法读取归档 " + origin, failure);
        } finally {
            roots.next();
        }
    }

    private String resourceName(URL url) {
        return URI.create("/" + url.getPath().substring(prefix.length())).getPath().substring(1);
    }

    private URL resource(String realName) throws IOException {
        try { return URI.create("jar:" + prefix + new URI(null, null, "/" + realName, null).getRawPath().substring(1)).toURL(); }
        catch (URISyntaxException ex) { throw new IOException("无效的归档条目 " + realName, ex); }
    }

    @Override protected synchronized String toExternalForm(URL url) {
        if (url.getPath().startsWith(prefix) && url.getPath().length() > prefix.length()) {
            String name = resourceName(url);
            if (directory != null) {
                URL file = directory.findResource(name);
                if (file != null) return file.toExternalForm();
            } else if (jar != null) {
                var entry = jar.getJarEntry(name);
                if (entry != null) {
                    try { return resource(entry.getRealName()).toExternalForm(); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
            }
        }
        return super.toExternalForm(url);
    }

    @Override protected synchronized URLConnection openConnection(URL url) throws IOException {
        open();
        String name = resourceName(url);
        if (directory != null) {
            URL file = directory.findResource(name);
            if (file == null) throw new FileNotFoundException(name);
            return file.openConnection();
        }
        if (jar == null || rejected.contains(name)) throw new FileNotFoundException(name);
        var cached = recent.get(name);
        if (cached != null) return cached;
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null) throw new FileNotFoundException(name);
        URL source = resource(entry.getRealName());
        if (!name.endsWith(".class") || name.startsWith("META-INF/")) return source.openConnection();
        byte[] raw;
        try (var in = jar.getInputStream(entry)) { raw = in.readAllBytes(); }
        catch (SecurityException failure) { throw new LinkageError("无法读取签名类 " + source, failure); }
        catch (ZipException | java.io.EOFException corrupt) { return reject(name, source, corrupt); }
        byte[] bytes;
        try {
            bytes = mod || game ? roots.normalizer.apply(name.substring(0, name.length() - 6), raw) : raw;
        } catch (IllegalArgumentException invalid) {
            if (entry.getCodeSigners() != null) throw new LinkageError("签名类格式无效 " + source, invalid);
            return reject(name, source, invalid);
        }
        if (entry.getCodeSigners() != null) {
            if (bytes != raw) throw new LinkageError("签名类需要改写 " + source);
            if (name.indexOf('/') < 0 || name.startsWith("net/minecraft/")) {
                throw new LinkageError("RFB 无法传递该包的类签名 " + source);
            }
        }
        var connection = new JarURLConnection(source) {
            @Override public void connect() { }
            // RFB 通过 getURL 设置类来源，通过 getJarFileURL 检查包密封。
            @Override public URL getURL() { return definition; }
            @Override public URL getJarFileURL() { return definition; }
            @Override public JarFile getJarFile() { return jar; }
            @Override public JarEntry getJarEntry() { return entry; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
        };
        recent.put(name, connection);
        if (recent.size() > 16) recent.pollFirstEntry();
        return connection;
    }

    private URLConnection reject(String name, URL source, Exception cause) throws IOException {
        if (!mod) throw new LinkageError("归档类格式无效 " + source, cause);
        rejected.add(name);
        LOG.log(System.Logger.Level.WARNING, "跳过损坏的模组类 " + source, cause);
        throw new FileNotFoundException(name);
    }

}
