package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 目标类：{@code com/genir/aitweaks/launcher/loading/CoreLoaderManager$BootstrapLoader}。<br>
 * 注入位置：构造函数内 {@code URLClassLoader.<init>([Ljava/net/URL;)V} 调用点。<br>
 * 注入动机：AITweaks 的 CoreLoader（负责加载 aitweaks-core.jar 并做混淆还原）由
 * CoreLoaderManager 静态块里的 BootstrapLoader 加载，而 BootstrapLoader 调
 * {@code URLClassLoader(URL[])} 单参构造——父加载器固定为系统类加载器，
 * 父优先委派在系统路径上找不到 aitweaks-launcher.jar，最终由 BootstrapLoader 自身
 * defineClass CoreLoader。<b>整条链路完全不经过 LaunchClassLoader 的 transformer 链</b>，
 * 导致 {@link AITweaksCoreLoaderProcessor} 的 defineClass 钩子从不触发，
 * aitweaks-core.jar 内全部类（如 ShieldAssistIndicator）的 lwjgl 调用在渲染线程分离
 * 模式下直奔真实 GL，主线程报 {@code No OpenGL context found in the current thread}。<br>
 * <p>
 * 为什么不用 Mixin：与 {@link AITweaksCoreLoaderProcessor} 同理——目标类位于
 * aitweaks-launcher.jar，Mixin 配置在游戏引导期解析目标时字节不可得，会被永久丢弃；
 * 且本改写是构造调用点的指令级替换（构造参数栈形变更），不属于 Mixin 注入模型。<br>
 * <p>
 * 注入效果：把单参构造调用替换为双参构造
 * {@code URLClassLoader.<init>([Ljava/net/URL;Ljava/lang/ClassLoader;)V}，
 * 父加载器传入 BootstrapLoader 自身的定义类加载器（即 LaunchClassLoader：
 * BootstrapLoader 由 CoreLoaderManager 引用，随之一同经 LaunchClassLoader 定义）。
 * 父优先委派随后把 CoreLoader 的加载送入 LaunchClassLoader transformer 链，
 * {@link AITweaksCoreLoaderProcessor} 得以生效。非分离模式下该改写同样安全：
 * 仅改变 CoreLoader 的定义类加载器归属，aitweaks-core.jar 的加载路径
 * （CoreLoader 自身构造与 loadClass 委派逻辑）不受影响。
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
                            // 栈形 [this, urls]：追加父加载器实参（自身定义类加载器，即
                            // LaunchClassLoader），再调双参构造。 ldc 自身类不触碰
                            // 未初始化的 this，校验器允许作为 super() 实参表达式。
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
