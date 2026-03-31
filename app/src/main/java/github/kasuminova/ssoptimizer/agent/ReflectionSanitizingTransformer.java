package github.kasuminova.ssoptimizer.agent;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;

/**
 * Intercepts reflection calls (Class.getMethod, Class.getDeclaredMethod,
 * Class.getField, Class.getDeclaredField) and redirects them to
 * {@link ReflectionHelper} which translates obfuscated names before
 * performing the actual lookup.
 *
 * <p>This transformer rewrites:
 * <pre>
 *   INVOKEVIRTUAL java/lang/Class.getMethod (Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
 * </pre>
 * to:
 * <pre>
 *   INVOKESTATIC github/.../ReflectionHelper.getMethod (Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
 * </pre>
 * The stack already has (classRef, nameString, paramArray) so changing
 * from invokevirtual to invokestatic with the receiver as first arg
 * is ABI-compatible.
 */
public final class ReflectionSanitizingTransformer implements ClassFileTransformer {

    private static final String HELPER = "github/kasuminova/ssoptimizer/agent/ReflectionHelper";

    /**
     * Maps Class method name → descriptor of the static replacement in ReflectionHelper.
     */
    private static final Map<String, String[]> REDIRECTS = Map.of(
            "getMethod", new String[]{
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                    "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"
            },
            "getDeclaredMethod", new String[]{
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                    "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"
            },
            "getField", new String[]{
                    "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                    "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;"
            },
            "getDeclaredField", new String[]{
                    "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                    "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;"
            }
    );

    private static boolean isSkipped(String className) {
        if (!BootstrapSearchInstaller.isHelperVisibilityReady()) {
            // Safe fallback for environments where the helper jar could not be
            // exposed to the bootstrap loader yet: only rewrite base game code.
            return !className.startsWith("com/fs/");
        }

        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("org/spongepowered/asm/")
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
        if (isJaninoLoader(loader) || isSkipped(className)) {
            return null;
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(0);
            boolean[] modified = {false};

            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDesc, boolean itf) {
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "java/lang/Class".equals(owner)) {
                                String[] redirect = REDIRECTS.get(methodName);
                                if (redirect != null && redirect[0].equals(methodDesc)) {
                                    // Rewrite: invokevirtual Class.xxx → invokestatic ReflectionHelper.xxx
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER,
                                            methodName, redirect[1], false);
                                    modified[0] = true;
                                    return;
                                }
                            }
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                        }
                    };
                }
            }, 0);

            return modified[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
