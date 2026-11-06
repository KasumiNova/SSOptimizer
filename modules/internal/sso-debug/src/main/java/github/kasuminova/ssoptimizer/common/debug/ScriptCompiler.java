package github.kasuminova.ssoptimizer.common.debug;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScriptCompiler {
    public Map<String, byte[]> compile(final String className, final String source) {
        final JavaCompiler compiler = systemCompiler();
        if (compiler == null) {
            throw new IllegalStateException("[SSOptimizer] ScriptCompiler requires a JDK (javax.tools.JavaCompiler unavailable)");
        }
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        final String simpleName = className.substring(className.lastIndexOf('.') + 1);
        final JavaFileObject sourceObject = new SimpleJavaFileObject(
                URI.create("string:///" + simpleName + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                return source;
            }
        };
        // JavacFileManager 缓存 classpath jar 的 zip 索引和句柄，必须关闭以防泄漏。
        try (StandardJavaFileManager standard =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            final MemoryFileManager fileManager = new MemoryFileManager(standard);
            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics,
                    List.of("-classpath", compilationClasspath()),
                    null, List.of(sourceObject));
            if (!task.call()) {
                final StringBuilder message = new StringBuilder("[SSOptimizer] Script compilation failed:");
                for (final Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    message.append('\n').append(diagnostic.getKind())
                            .append(" line ").append(diagnostic.getLineNumber())
                            .append(": ").append(diagnostic.getMessage(null));
                }
                throw new ScriptCompileException(message.toString());
            }
            return fileManager.compiledClasses();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("[SSOptimizer] ScriptCompiler file manager close failed", e);
        }
    }

    /** RFB 自定义系统类加载器可能使 ToolProvider 返回 null，需通过 boot 模块层 ServiceLoader 查找 jdk.compiler 提供者。 */
    private static JavaCompiler systemCompiler() {
        final JavaCompiler direct = ToolProvider.getSystemJavaCompiler();
        if (direct != null) {
            return direct;
        }
        for (final JavaCompiler candidate : java.util.ServiceLoader.load(
                ModuleLayer.boot(), JavaCompiler.class)) {
            return candidate;
        }
        return null;
    }

    private static String compilationClasspath() {
        final Set<String> entries = new LinkedHashSet<>(List.of(
                System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)));
        for (ClassLoader loader = ScriptCompiler.class.getClassLoader();
             loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlClassLoader) {
                for (final java.net.URL url : urlClassLoader.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        try {
                            entries.add(new java.io.File(url.toURI()).getAbsolutePath());
                        } catch (final java.net.URISyntaxException e) {
                            throw new IllegalStateException("[SSOptimizer] Bad classpath URL: " + url, e);
                        }
                    }
                }
            }
        }
        entries.remove("");
        return String.join(java.io.File.pathSeparator, entries);
    }

    public static final class ScriptCompileException extends RuntimeException {
        public ScriptCompileException(final String message) {
            super(message);
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteClassFile> compiled = new LinkedHashMap<>();

        private MemoryFileManager(final StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(final Location location, final String className,
                                                   final JavaFileObject.Kind kind, final FileObject sibling) {
            final ByteClassFile file = new ByteClassFile(className, kind);
            compiled.put(className, file);
            return file;
        }

        private Map<String, byte[]> compiledClasses() {
            final Map<String, byte[]> result = new LinkedHashMap<>();
            compiled.forEach((name, file) -> result.put(name, file.bytes()));
            return result;
        }
    }

    private static final class ByteClassFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private ByteClassFile(final String className, final Kind kind) {
            super(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return out;
        }

        private byte[] bytes() {
            return out.toByteArray();
        }
    }
}
