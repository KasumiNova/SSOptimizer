package github.kasuminova.ssoptimizer.bootstrap;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;

/**
 * 反射调用净化变换器，拦截反射调用并重定向到 {@link ReflectionHelper}，
 * 以便在查找前将混淆名称翻译为净化后的名称。
 * <p>
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

    private static final String HELPER = "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper";

    /**
     * Class 方法名 → [原始描述符, ReflectionHelper 中替代方法的描述符] 映射表。
     * <p>
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

    /**
     * {@inheritDoc}
     * <p>
     * 遍历类中所有方法的字节码，将 {@code invokevirtual Class.getMethod/getField} 等
     * 调用重写为 {@code invokestatic ReflectionHelper} 的对应方法。
     * 这里不能跳过 Janino 脚本类：大量模组源码会由
     * {@code org.codehaus.janino.JavaSourceClassLoader} 在运行时编译加载，
     * 这些类同样可能通过 {@link Class#getDeclaredField(String)} /
     * {@link Class#getDeclaredMethod(String, Class[])} 反射访问已被 remap 或 sanitize
     * 的引擎成员。若跳过 Janino loader，就会漏掉这类第三方模组兼容。
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
        if (isSkipped(className)) {
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
