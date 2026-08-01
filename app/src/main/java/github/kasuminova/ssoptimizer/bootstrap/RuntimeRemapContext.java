package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.BytecodeRemapper;
import github.kasuminova.ssoptimizer.mapping.MappingDirection;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.apache.log4j.Logger;

import java.util.Objects;

/**
 * 运行时重映射上下文。
 * <p>
 * 该上下文以 Tiny v2 映射仓库为事实来源，负责把类、字段和方法从混淆命名翻译为
 * 可读命名，供 {@link RuntimeRemapTransformer} 在类加载早期使用。
 */
public final class RuntimeRemapContext {
    /**
     * 全量 deobf 模式开关的系统属性。
     * <p>
     * 置 {@code true} 时 agent 加载全量映射表（约 22 万条目/平台），对 mod 字节码做
     * obf→named 全类覆写；默认 {@code false}，维持 35 类桥接小表行为。
     */
    public static final String FULL_DEOBF_PROPERTY = "ssoptimizer.deobf.full";

    private static final Logger LOGGER = Logger.getLogger(RuntimeRemapContext.class);

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
     * 判断全量 deobf 模式是否启用。
     *
     * @return 系统属性 {@link #FULL_DEOBF_PROPERTY} 为 {@code true} 时返回 {@code true}
     */
    public static boolean isFullDeobfEnabled() {
        return Boolean.getBoolean(FULL_DEOBF_PROPERTY);
    }

    /**
     * 加载默认 classpath 映射资源创建上下文。
     * <p>
     * 全量 deobf 开关开启时加载 gzip 全量映射表，否则加载 35 类桥接小表；
     * 选定后在日志输出表来源、条目数与加载耗时。
     * <p>
     * 注意：本类由 app 类加载器加载，而 {@link TinyV2MappingRepository} 在运行期
     * 由 bootstrap 类加载器加载。此处不得引用 {@code MappingPlatform} 类型
     * （包括调用以它为参数/返回值的方法），否则跨加载器记录 loader constraint
     * 会让 bootstrap 侧后续调用以 {@link LinkageError} 崩溃。
     *
     * @return 默认运行时重映射上下文
     */
    public static RuntimeRemapContext loadDefault() {
        final long loadStartNanos = System.nanoTime();
        final TinyV2MappingRepository loaded;
        final String resourcePath;
        if (isFullDeobfEnabled()) {
            resourcePath = TinyV2MappingRepository.defaultFullResourcePath();
            loaded = TinyV2MappingRepository.loadFullDefault();
        } else {
            resourcePath = TinyV2MappingRepository.defaultResourcePath();
            loaded = TinyV2MappingRepository.loadDefault();
        }
        LOGGER.info("[SSOptimizer] Runtime remap mapping table loaded: " + resourcePath
                + " (" + loaded.entries().size() + " entries, "
                + (System.nanoTime() - loadStartNanos) / 1_000_000 + " ms)");
        return new RuntimeRemapContext(loaded);
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
            // remap 失败不得阻断类加载：原样放行混淆字节码，但必须留下可追查的日志。
            LOGGER.warn("[SSOptimizer] Runtime remap failed for class: " + className, throwable);
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