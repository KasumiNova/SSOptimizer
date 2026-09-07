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

/**
 * 调试脚本编译器：javax.tools 内存编译。
 *
 * <p>动机：调试脚本需要「源码进、类字节出」的秒级编译，产物不落盘。
 * 运行时为完整 JDK（zulu25），{@link ToolProvider#getSystemJavaCompiler()} 可用。
 * 编译 classpath 取 {@code java.class.path} 叠加类加载器链上 URLClassLoader
 * 的 URLs（RFB LaunchClassLoader 是 URLClassLoader 子类，游戏 jar 经此可见）。</p>
 */
public final class ScriptCompiler {
    /**
     * 编译单个源文件，返回「类全名 → 类字节」映射（含内部类）。
     *
     * @param className 源文件中主类的全限定名（决定源文件名与后续加载入口）
     * @param source    Java 源码全文
     * @return 编译产物映射
     * @throws ScriptCompileException 编译失败，message 含完整诊断信息
     */
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
        // JavacFileManager 缓存 classpath jar 的 zip 索引/句柄，必须关闭防泄漏
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

    /**
     * 获取系统 Java 编译器。首选 {@link ToolProvider#getSystemJavaCompiler()}；
     * RFB 自定义系统类加载器下该调用因服务提供者查找失败而返回 null，
     * 此时回退到 boot 模块层的 ServiceLoader（jdk.compiler 的提供者声明在
     * module-info，类加载器路径的 ServiceLoader 看不到模块服务声明，
     * 必须用模块层感知重载；标准 API 用法，非反射）。两者皆空返回 null。
     *
     * @return Java 编译器实例，不可用时为 null
     */
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

    /**
     * 脚本编译失败异常，message 携带 javax.tools 诊断全文。
     */
    public static final class ScriptCompileException extends RuntimeException {
        public ScriptCompileException(final String message) {
            super(message);
        }
    }

    /**
     * 内存编译产物文件管理器：类字节写入内存映射而非磁盘。
     */
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

    /**
     * 内存类字节输出对象。
     */
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
