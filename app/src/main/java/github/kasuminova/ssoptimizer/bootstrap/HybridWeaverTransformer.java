package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.MappingRepository;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.apache.log4j.Logger;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 混合织入变换器，同时支持 ASM 和 Mixin 两种字节码注入方式。
 * <p>
 * 内部维护一个“类名 → 处理器”的注册表，在类加载时匹配并执行对应的
 * {@link AsmClassProcessor}。类名统一使用 JVM 内部格式（{@code /} 分隔）。
 */
public final class HybridWeaverTransformer implements ClassFileTransformer {
    private static final Logger                         LOGGER     = Logger.getLogger(HybridWeaverTransformer.class);
    private final        Map<String, AsmClassProcessor> processors = new ConcurrentHashMap<>();
    private final        MappingRepository              mappings;

    /**
     * 使用默认 Tiny v2 映射仓库创建混合织入变换器。
     */
    public HybridWeaverTransformer() {
        this(TinyV2MappingRepository.loadDefault());
    }

    HybridWeaverTransformer(MappingRepository mappings) {
        this.mappings = mappings;
    }

    /**
     * 注册指定类名的 ASM 字节码处理器。
     *
     * @param className 目标类名（点号或斜杠分隔均可，内部统一转换为斜杠格式）
     * @param processor 处理器实例
     */
    public void registerProcessor(String className, AsmClassProcessor processor) {
        processors.put(normalizeClassName(className), processor);
    }

    /**
     * 移除指定类名的处理器注册。
     *
     * @param className 目标类名
     */
    public void removeProcessor(String className) {
        processors.remove(normalizeClassName(className));
    }

    /**
     * 获取当前已注册的处理器数量。
     */
    public int getProcessorCount() {
        return processors.size();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 在类加载时检查是否有匹配的处理器，若有则执行字节码转换。
     */
    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }

        AsmClassProcessor processor = processors.get(className);
        if (processor == null) {
            String translatedClassName = mappings.findClassByObfuscatedName(className)
                                                 .map(github.kasuminova.ssoptimizer.mapping.MappingEntry::namedName)
                                                 .orElse(null);
            if (translatedClassName != null) {
                processor = processors.get(translatedClassName);
            }
        }
        if (processor == null) {
            return null;
        }

        try {
            byte[] result = processor.process(classfileBuffer);
            if (result != null) {
                LOGGER.debug("[SSOptimizer] Processed class: " + className);
            }
            return result;
        } catch (Throwable t) {
            LOGGER.error("[SSOptimizer] ASM processor failed for " + className, t);
            return null;
        }
    }

    private String normalizeClassName(String className) {
        return className.replace('.', '/');
    }
}
