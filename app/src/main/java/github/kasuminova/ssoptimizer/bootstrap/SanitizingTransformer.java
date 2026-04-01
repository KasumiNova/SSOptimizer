package github.kasuminova.ssoptimizer.bootstrap;

import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 类名净化变换器，在类加载时将超长混淆标识符替换为可读的短名称。
 * <p>
 * 通过 {@link SanitizingRemapper} 对游戏引擎类中含有非法字符的方法名、字段名、
 * invokedynamic 方法名等进行转义替换，使它们成为合法的 JVM 标识符。
 * 跳过 JDK 内部类、ASM 自身和 SSOptimizer 自身的类。
 */
public final class SanitizingTransformer implements ClassFileTransformer {
    private static final Logger LOGGER = Logger.getLogger(SanitizingTransformer.class);

    private static boolean isKnownSafe(String className) {
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("github/kasuminova/ssoptimizer/");
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
     * 对非安全名单内的类应用 {@link SanitizingRemapper}，将非法标识符替换为合法形式。
     */
    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        if (isJaninoLoader(loader)) {
            return null;
        }
        if (isKnownSafe(className)) {
            return null;
        }

        try {
            SanitizingRemapper remapper = new SanitizingRemapper();
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remapper), 0);

            if (!remapper.isModified()) {
                return null;
            }

            LOGGER.debug("[SSOptimizer] Sanitized illegal identifiers in " + className);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.error("[SSOptimizer] Sanitizer failed for " + className, t);
            return null;
        }
    }
}
