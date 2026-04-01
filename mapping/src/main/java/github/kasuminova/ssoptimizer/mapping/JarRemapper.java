package github.kasuminova.ssoptimizer.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * JAR 级别的批量重映射器。
 * <p>
 * 该实现会遍历输入 JAR 中的 class 条目并使用 {@link BytecodeRemapper} 重写类名与成员名，
 * 同时保留普通资源文件。为避免 remap 后签名失效，会自动剔除 {@code META-INF/*.SF}、
 * {@code *.DSA} 与 {@code *.RSA} 条目。
 */
public final class JarRemapper {
    private final BytecodeRemapper bytecodeRemapper;

    /**
     * 创建 JAR 重映射器。
     *
     * @param repository 映射仓库
     * @param direction  映射方向
     */
    public JarRemapper(MappingRepository repository, MappingDirection direction) {
        this.bytecodeRemapper = new BytecodeRemapper(repository, direction);
    }

    /**
     * 将输入 JAR 重映射到输出路径。
     *
     * @param inputJar  输入 JAR
     * @param outputJar 输出 JAR
     * @throws IOException 若读写失败
     */
    public void remapJar(Path inputJar, Path outputJar) throws IOException {
        Objects.requireNonNull(inputJar, "inputJar");
        Objects.requireNonNull(outputJar, "outputJar");

        Files.createDirectories(outputJar.toAbsolutePath().getParent());

        try (JarFile jarFile = new JarFile(inputJar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            try (OutputStream fileStream = Files.newOutputStream(outputJar);
                 JarOutputStream outputStream = manifest == null
                         ? new JarOutputStream(fileStream)
                         : new JarOutputStream(fileStream, manifest)) {
                Set<String> writtenEntries = new HashSet<>();
                if (manifest != null) {
                    writtenEntries.add("META-INF/MANIFEST.MF");
                }

                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || shouldSkip(entry.getName())) {
                        continue;
                    }
                    if (manifest != null && "META-INF/MANIFEST.MF".equals(entry.getName())) {
                        continue;
                    }

                    try (InputStream inputStream = jarFile.getInputStream(entry)) {
                        byte[] bytes = inputStream.readAllBytes();
                        if (entry.getName().endsWith(".class")) {
                            BytecodeRemapper.RemappedClass remappedClass = bytecodeRemapper.remapClass(bytes);
                            writeEntry(outputStream, writtenEntries, remappedClass.outputInternalName() + ".class", remappedClass.bytecode());
                        } else {
                            writeEntry(outputStream, writtenEntries, entry.getName(), bytes);
                        }
                    }
                }
            }
        }
    }

    private static boolean shouldSkip(String entryName) {
        if (!entryName.startsWith("META-INF/")) {
            return false;
        }
        return entryName.endsWith(".SF") || entryName.endsWith(".DSA") || entryName.endsWith(".RSA");
    }

    private static void writeEntry(JarOutputStream outputStream,
                                   Set<String> writtenEntries,
                                   String entryName,
                                   byte[] bytes) throws IOException {
        if (!writtenEntries.add(entryName)) {
            return;
        }
        JarEntry outputEntry = new JarEntry(entryName);
        outputStream.putNextEntry(outputEntry);
        outputStream.write(bytes);
        outputStream.closeEntry();
    }
}
