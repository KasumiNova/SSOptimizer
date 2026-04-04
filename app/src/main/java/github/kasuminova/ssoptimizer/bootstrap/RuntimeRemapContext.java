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
    private final BytecodeRemapper        bytecodeRemapper;
    private final TinyV2MappingRepository repository;

    /**
     * 使用指定映射仓库创建上下文。
     *
     * @param repository Tiny v2 映射仓库
     */
    public RuntimeRemapContext(TinyV2MappingRepository repository) {
        Objects.requireNonNull(repository, "repository");
        this.bytecodeRemapper = new BytecodeRemapper(repository, MappingDirection.OBFUSCATED_TO_NAMED);
        this.repository = repository;
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
     * <p>
     * 即使类名本身在 named 侧保持不变，只要该类的字段或方法存在映射，这里也必须
     * 尝试重映射，以确保运行时优先暴露 mapped 命名。
     *
     * @param className       JVM 内部类名
     * @param classfileBuffer 原始字节码
     * @return 重映射后的字节码；若没有映射则返回 {@code null}
     */
    public byte[] remap(String className, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null || isKnownSafe(className)) {
            return null;
        }

        try {
            BytecodeRemapper.RemappedClass remappedClass = bytecodeRemapper.remapClass(classfileBuffer);
            return remappedClass.modified() ? remappedClass.bytecode() : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * 将混淆类名翻译为可读命名。
     * <p>
     * 仅查询 Tiny v2 映射表的类条目；若没有对应映射则原样返回输入名，
     * 保证合法 JVM 内部类名中的 {@code /} 不被污染。
     *
     * @param className JVM 内部格式的混淆类名
     * @return 可读类名（JVM 内部格式），没有映射时返回原值
     */
    public String translateClassName(String className) {
        return repository.findClassByObfuscatedName(className)
                .map(MappingEntry::namedName)
                .orElse(className);
    }
}