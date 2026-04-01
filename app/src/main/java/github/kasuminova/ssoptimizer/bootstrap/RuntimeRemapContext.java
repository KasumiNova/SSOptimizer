package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.BytecodeRemapper;
import github.kasuminova.ssoptimizer.mapping.MappingDirection;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.util.Objects;

/**
 * 运行时重映射上下文。
 * <p>
 * 该上下文以 Tiny v2 映射仓库为事实来源，负责把类、字段和方法从混淆命名翻译为
 * 可读命名，供 {@link RuntimeRemapTransformer} 在类加载早期使用。
 */
public final class RuntimeRemapContext {
    private final TinyV2MappingRepository repository;
    private final BytecodeRemapper        bytecodeRemapper;

    /**
     * 使用指定映射仓库创建上下文。
     *
     * @param repository Tiny v2 映射仓库
     */
    public RuntimeRemapContext(TinyV2MappingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.bytecodeRemapper = new BytecodeRemapper(repository, MappingDirection.OBFUSCATED_TO_NAMED);
    }

    /**
     * 加载默认 classpath 映射资源创建上下文。
     *
     * @return 默认运行时重映射上下文
     */
    public static RuntimeRemapContext loadDefault() {
        return new RuntimeRemapContext(TinyV2MappingRepository.loadDefault());
    }

    private static boolean isKnownSafe(String className) {
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("org/spongepowered/asm/")
                || className.startsWith("github/kasuminova/ssoptimizer/");
    }

    /**
     * 重映射指定类字节码。
     *
     * @param className       JVM 内部类名
     * @param classfileBuffer 原始字节码
     * @return 重映射后的字节码；若没有映射则返回 {@code null}
     */
    public byte[] remap(String className, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null || isKnownSafe(className)) {
            return null;
        }

        MappingEntry classEntry = repository.findClassByObfuscatedName(className).orElse(null);
        if (classEntry == null) {
            return null;
        }

        try {
            BytecodeRemapper.RemappedClass remappedClass = bytecodeRemapper.remapClass(classfileBuffer);
            return remappedClass.modified() ? remappedClass.bytecode() : null;
        } catch (Throwable throwable) {
            return null;
        }
    }
}