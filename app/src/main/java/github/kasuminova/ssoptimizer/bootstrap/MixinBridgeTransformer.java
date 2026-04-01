package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mixin.service.AgentMixinService;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Mixin 框架桥接变换器，将 Mixin 的类变换桥接到 javaagent 的 {@link ClassFileTransformer}。
 * <p>
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
    private final RuntimeRemapContext remapContext;

    /**
     * 构造桥接变换器，从 Mixin 服务中获取或创建变换器实例。
     *
     * @throws IllegalStateException 若 Mixin 服务类型不是 {@link AgentMixinService}
     */
    public MixinBridgeTransformer() {
        IMixinService service = MixinService.getService();
        if (!(service instanceof AgentMixinService agentService)) {
            throw new IllegalStateException("Unexpected Mixin service: " + service.getClass().getName());
        }
        this.transformer = agentService.getOrCreateTransformer();
        this.remapContext = RuntimeRemapContext.loadDefault();
    }

    /**
     * 判断指定类名是否应跳过 Mixin 处理。
     * <p>
     * 跳过 JDK 内部类、ASM、Mixin 自身、SSOptimizer 自身，以及非游戏类。
     *
     * @param className JVM 内部格式的类名
     * @return 是否跳过
     */
    static boolean shouldSkipClass(String className) {
        return shouldSkipClass(null, className);
    }

    /**
     * 判断指定类加载器和类名是否应跳过 Mixin 处理。
     * <p>
     * 额外跳过 Janino 动态编译器加载的类。
     *
     * @param loader    类加载器，可能为 {@code null}
     * @param className JVM 内部格式的类名
     * @return 是否跳过
     */
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

    /**
     * {@inheritDoc}
     * <p>
     * 将类字节码路由到 Mixin 变换器，使其能够应用 Mixin 配置。
     */
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
            String namedInternalName = NameTranslator.translate(className);
            String namedDottedName = namedInternalName.replace('/', '.');
            byte[] namedBytes = remapContext.remap(className, classfileBuffer);
            byte[] mixinInput = namedBytes != null ? namedBytes : classfileBuffer;
            String mixinName = namedBytes != null ? namedDottedName : className.replace('/', '.');

            byte[] result = transformer.transformClassBytes(mixinName, namedDottedName, mixinInput);
            if (result != null && result != classfileBuffer) {
                LOGGER.info("[SSOptimizer] Mixin-applied class: " + className + " -> " + namedDottedName);
            }
            return result;
        } catch (Throwable t) {
            LOGGER.error("[SSOptimizer] Mixin bridge failed for " + className, t);
            return null;
        }
    }
}