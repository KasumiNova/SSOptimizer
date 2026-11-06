package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NanoForge transformer 链上的 ASM 处理器分发器，输入字节码使用 named 名称。
 * 全局处理器先于按类名匹配的处理器执行；重入时透传原字节，避免类定义循环。
 * RFB 会无条件采纳返回值，因此未命中、未修改或处理器异常时都必须返回原字节。
 */
public final class HybridWeaverTransformer implements IClassTransformer {
    private static final Logger LOGGER = Logger.getLogger(HybridWeaverTransformer.class);

    private static final Map<String, AsmClassProcessor> PROCESSORS = new ConcurrentHashMap<>();

    /** 全局处理器必须自带廉价预过滤，避免逐类进行全量 ASM 解析。 */
    private static final List<AsmClassProcessor> GLOBAL_PROCESSORS = new CopyOnWriteArrayList<>();

    /** 同类重入只用于 Mixin 分析，透传原字节；定义期变换由最外层调用完成。 */
    private static final ThreadLocal<Set<String>> IN_FLIGHT = ThreadLocal.withInitial(HashSet::new);

    public HybridWeaverTransformer() {
    }

    public static void registerProcessor(String className, AsmClassProcessor processor) {
        PROCESSORS.put(normalizeClassName(className), processor);
    }

    public static void removeProcessor(String className) {
        PROCESSORS.remove(normalizeClassName(className));
    }

    public static void registerGlobalProcessor(AsmClassProcessor processor) {
        GLOBAL_PROCESSORS.add(processor);
    }

    public static int getProcessorCount() {
        return PROCESSORS.size() + GLOBAL_PROCESSORS.size();
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        String className = transformedName != null ? transformedName : name;
        if (className == null) {
            return basicClass;
        }

        String key = normalizeClassName(className);
        AsmClassProcessor processor = PROCESSORS.get(key);
        if (processor == null && GLOBAL_PROCESSORS.isEmpty()) {
            return basicClass;
        }

        Set<String> inFlight = IN_FLIGHT.get();
        if (!inFlight.add(key)) {
            return basicClass;
        }
        try {
            byte[] current = basicClass;
            boolean processed = false;
            for (AsmClassProcessor global : GLOBAL_PROCESSORS) {
                try {
                    byte[] result = global.process(current);
                    if (result != null) {
                        current = result;
                        processed = true;
                    }
                } catch (Throwable t) {
                    LOGGER.error("[SSOptimizer] Global ASM processor failed for " + className, t);
                }
            }
            if (processor != null) {
                try {
                    byte[] result = processor.process(current);
                    if (result != null) {
                        current = result;
                        processed = true;
                    }
                } catch (Throwable t) {
                    LOGGER.error("[SSOptimizer] ASM processor failed for " + className, t);
                }
            }
            if (processed) {
                LOGGER.debug("[SSOptimizer] Processed class: " + className);
            }
            return current;
        } finally {
            inFlight.remove(key);
        }
    }

    private static String normalizeClassName(String className) {
        return className.replace('.', '/');
    }
}
