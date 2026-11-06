package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 将 BootstrapLoader 的父加载器改为 LaunchClassLoader，使 CoreLoader 进入类变换链。
 * <p>目标类在 Mixin 解析时尚不可加载，且构造调用替换涉及参数栈调整，因此使用 ASM。</p>
 */
public final class AITweaksBootstrapLoaderProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS =
            "com/genir/aitweaks/launcher/loading/CoreLoaderManager$BootstrapLoader";
    public static final String SUPER_OWNER = "java/net/URLClassLoader";
    public static final String SINGLE_ARG_DESC = "([Ljava/net/URL;)V";
    public static final String DUAL_ARG_DESC = "([Ljava/net/URL;Ljava/lang/ClassLoader;)V";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name) || !"()V".equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(final int opcode,
                                                final String owner,
                                                final String name,
                                                final String descriptor,
                                                final boolean isInterface) {
                        if (opcode == Opcodes.INVOKESPECIAL
                                && SUPER_OWNER.equals(owner)
                                && "<init>".equals(name)
                                && SINGLE_ARG_DESC.equals(descriptor)) {
                            // 追加父加载器参数时使用自身类常量，避免在 super() 实参中读取未初始化的 this。
                            modified[0] = true;
                            super.visitLdcInsn(Type.getObjectType(TARGET_CLASS));
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                                    "getClassLoader", "()Ljava/lang/ClassLoader;", false);
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, SUPER_OWNER,
                                    "<init>", DUAL_ARG_DESC, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
