package ssoptimizer.standalone.host;

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
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

/** RFB 从原归档连接读取保护域、包清单和签名，从已验证的实例缓存读取对应条目的字节。 */
final class ModArchiveView extends URLStreamHandler {
    private final URLClassLoader originals;
    private final Map<Path, Path> views;
    private final String prefix;

    private ModArchiveView(Path origin, Map<Path, Path> views) throws IOException {
        originals = new URLClassLoader(new URL[]{origin.toUri().toURL()}, null);
        this.views = views;
        prefix = origin.toUri().toASCIIString() + "!/";
    }

    static URL open(Path origin, Map<Path, Path> views) throws IOException {
        var handler = new ModArchiveView(origin, views);
        return URL.of(URI.create("jar:" + handler.prefix), handler);
    }

    private String resourceName(URL url) {
        return URI.create("/" + url.getPath().substring(prefix.length())).getPath().substring(1);
    }

    @Override protected String toExternalForm(URL url) {
        if (url.getPath().length() > prefix.length() && url.getPath().startsWith(prefix)) {
            URL resource = originals.findResource(resourceName(url));
            if (resource != null) return resource.toExternalForm();
        }
        return super.toExternalForm(url);
    }

    @Override protected URLConnection openConnection(URL url) throws IOException {
        try {
            String name = resourceName(url);
            var resources = originals.findResources(name);
            while (resources.hasMoreElements()) {
                URL original = resources.nextElement();
                URLConnection connection = original.openConnection();
                if (!(connection instanceof JarURLConnection source) || !"file".equals(source.getJarFileURL().getProtocol())) return connection;
                Path origin = Path.of(source.getJarFileURL().toURI()).toRealPath();
                Path prepared = views.get(origin);
                if (prepared == null || prepared.equals(origin)) return view(source, source, name);
                String entry = new URI(null, null, "/" + source.getEntryName(), null).getRawPath();
                URL cached = URI.create("jar:" + prepared.toUri().toASCIIString() + "!" + entry).toURL();
                var content = (JarURLConnection) cached.openConnection();
                try { content.connect(); }
                catch (FileNotFoundException filtered) { continue; }
                return view(source, content, name);
            }
            throw new FileNotFoundException(name);
        } catch (URISyntaxException ex) {
            throw new IOException("无法解析模组条目：" + url, ex);
        }
    }

    private static URLConnection view(JarURLConnection source, JarURLConnection content, String name) throws IOException {
        if (source == content && !name.endsWith(".class")) return source;
        return new JarURLConnection(source.getURL()) {
            @Override public void connect() throws IOException { content.connect(); }
            @Override public JarFile getJarFile() throws IOException { return source.getJarFile(); }
            // RFB 1.0.12 直接用连接的 URL 为经过变换的类创建 ProtectionDomain。
            @Override public URL getURL() { return name.endsWith(".class") ? source.getJarFileURL() : source.getURL(); }
            @Override public InputStream getInputStream() throws IOException { return content.getInputStream(); }
            @Override public long getContentLengthLong() { return content.getContentLengthLong(); }
            @Override public int getContentLength() { return content.getContentLength(); }
        };
    }
}
