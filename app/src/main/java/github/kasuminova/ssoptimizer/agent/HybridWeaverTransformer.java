package github.kasuminova.ssoptimizer.agent;

import org.apache.log4j.Logger;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HybridWeaverTransformer implements ClassFileTransformer {
    private static final Logger                         LOGGER     = Logger.getLogger(HybridWeaverTransformer.class);
    private final        Map<String, AsmClassProcessor> processors = new ConcurrentHashMap<>();

    public void registerProcessor(String className, AsmClassProcessor processor) {
        processors.put(normalizeClassName(className), processor);
    }

    public void removeProcessor(String className) {
        processors.remove(normalizeClassName(className));
    }

    public int getProcessorCount() {
        return processors.size();
    }

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
