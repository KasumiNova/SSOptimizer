package github.kasuminova.ssoptimizer.testing;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取得由自定义加载器定义的生成类实例。生成类在静态初始化时自行 new 并登记，测试只触发初始化后取走实例，
 * 不经由 Constructor 或 ServiceLoader 实例化。
 */
public final class ClassInstances {
    private static final String OWNER = Type.getInternalName(ClassInstances.class);

    private static final Map<String, Object> PUBLISHED = new ConcurrentHashMap<>();

    private ClassInstances() {
    }

    /** 供生成类的静态初始化器调用。 */
    public static void publish(final String className,
                               final Object instance) {
        PUBLISHED.put(className, instance);
    }

    /** 为带无参构造器的生成类追加静态初始化器，使其在初始化时 new 自身并登记。 */
    public static byte[] publishInstanceOnInit(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        final String internalName = reader.getClassName();
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                final MethodVisitor init = cv.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                init.visitCode();
                init.visitLdcInsn(internalName.replace('/', '.'));
                init.visitTypeInsn(Opcodes.NEW, internalName);
                init.visitInsn(Opcodes.DUP);
                init.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false);
                init.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "publish",
                        "(Ljava/lang/String;Ljava/lang/Object;)V", false);
                init.visitInsn(Opcodes.RETURN);
                init.visitMaxs(0, 0);
                init.visitEnd();
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    public static <T> T instantiate(final Class<T> type,
                                    final Class<?> definedClass) {
        try {
            MethodHandles.lookup().ensureInitialized(definedClass);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法初始化动态定义的类: " + definedClass.getName(), e);
        }
        final Object instance = PUBLISHED.remove(definedClass.getName());
        if (instance == null) {
            throw new IllegalStateException("动态定义的类未登记实例: " + definedClass.getName());
        }
        return type.cast(instance);
    }
}
