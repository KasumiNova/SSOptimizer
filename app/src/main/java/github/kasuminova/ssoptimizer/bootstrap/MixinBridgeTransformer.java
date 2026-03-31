package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mixin.service.AgentMixinService;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Bridges Sponge Mixin's {@link IMixinTransformer} into the Java agent
 * instrumentation chain.
 * <p>
 * In our environment, bootstrapping Mixin and registering a config is not
 * enough by itself — we also need to route class bytes through the active
 * transformer during class loading.
 */
public final class MixinBridgeTransformer implements ClassFileTransformer {
    private static final Logger LOGGER = Logger.getLogger(MixinBridgeTransformer.class);

    private final IMixinTransformer transformer;

    public MixinBridgeTransformer() {
        IMixinService service = MixinService.getService();
        if (!(service instanceof AgentMixinService agentService)) {
            throw new IllegalStateException("Unexpected Mixin service: " + service.getClass().getName());
        }
        this.transformer = agentService.getOrCreateTransformer();
    }

    static boolean shouldSkipClass(String className) {
        return shouldSkipClass(null, className);
    }

    static boolean shouldSkipClass(ClassLoader loader, String className) {
        if (isJaninoLoader(loader)) {
            return true;
        }

        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("org/spongepowered/asm/")
                || className.startsWith("github/kasuminova/ssoptimizer/")
                || !className.startsWith("com/fs/");
    }

    private static boolean isJaninoLoader(ClassLoader loader) {
        for (Class<?> type = loader != null ? loader.getClass() : null; type != null; type = type.getSuperclass()) {
            if ("org.codehaus.janino.JavaSourceClassLoader".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null || shouldSkipClass(loader, className)) {
            return null;
        }

        try {
            String dottedName = className.replace('/', '.');
            byte[] result = transformer.transformClassBytes(dottedName, dottedName, classfileBuffer);
            if (result != null && result != classfileBuffer) {
                LOGGER.info("[SSOptimizer] Mixin-applied class: " + className);
            }
            return result;
        } catch (Throwable t) {
            LOGGER.error("[SSOptimizer] Mixin bridge failed for " + className, t);
            return null;
        }
    }
}