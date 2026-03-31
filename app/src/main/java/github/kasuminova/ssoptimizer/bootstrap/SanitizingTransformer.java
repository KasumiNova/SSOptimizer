package github.kasuminova.ssoptimizer.bootstrap;

import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

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
