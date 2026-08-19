package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code com/genir/aitweaks/launcher/loading/CoreLoader}。<br>
 * 注入位置：{@code loadClass(String)} 内对 {@code defineClass(String, byte[], int, int)} 的调用点。<br>
 * 注入动机：AITweaks 用自有 {@code URLClassLoader}（CoreLoader）加载 aitweaks-core.jar
 * （自带 Transformer 混淆处理后经 defineClass 定义），完全不经过 LaunchClassLoader 的
 * transformer 链——渲染线程分离模式下其 lwjgl 调用不会被 ASM 重定向到 bridge，
 * 在主线程直出即 {@code No OpenGL context found in the current thread}。<br>
 * <p>
 * 为什么不用 Mixin：Mixin 配置在游戏引导期（模组 jar 挂载到 LaunchClassLoader 之前）
 * 完成目标类解析，CoreLoader 位于 aitweaks-launcher.jar 内，此时字节不可得，
 * 目标会被永久丢弃（运行时实测：{@code @Mixin target ... was not found} WARN）。
 * ASM 处理器在类实际经 LaunchClassLoader 加载时才介入，不受解析时序影响。<br>
 * <p>
 * <b>前置依赖</b>：CoreLoader 默认由 AITweaks 自带的 BootstrapLoader（父加载器为系统
 * 类加载器）定义，并不经过 LaunchClassLoader——本处理器只有在
 * {@link AITweaksBootstrapLoaderProcessor} 把 BootstrapLoader 的父加载器改为
 * LaunchClassLoader 之后才会被触发，二者必须同时注册。<br>
 * <p>
 * 注入效果：向 CoreLoader 追加合成静态方法 {@code ssoptimizer$defineClassRedirected}，
 * 先把字节码交给 {@code RenderThreadRedirector.redirect}（非分离模式零开销原样返回），
 * 再回调原 defineClass；loadClass 内原 INVOKEVIRTUAL 调用点改写为该合成方法的 INVOKESTATIC。
 * 合成方法必须定义在 CoreLoader 内部：defineClass 是 protected final，
 * 只有同类内的代码才能以 CoreLoader 引用发起调用。
 */
public final class AITweaksCoreLoaderProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "com/genir/aitweaks/launcher/loading/CoreLoader";
    public static final String TARGET_METHOD = "loadClass";
    public static final String TARGET_DESC = "(Ljava/lang/String;)Ljava/lang/Class;";
    public static final String DEFINE_CLASS_DESC = "(Ljava/lang/String;[BII)Ljava/lang/Class;";
    public static final String HELPER_METHOD = "ssoptimizer$defineClassRedirected";
    public static final String HELPER_DESC = "(L" + TARGET_CLASS + ";Ljava/lang/String;[BII)Ljava/lang/Class;";
    public static final String REDIRECT_OWNER = "github/kasuminova/ssoptimizer/asm/render/RenderThreadRedirector";

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
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(final int opcode,
                                                final String owner,
                                                final String name,
                                                final String descriptor,
                                                final boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && TARGET_CLASS.equals(owner)
                                && "defineClass".equals(name)
                                && DEFINE_CLASS_DESC.equals(descriptor)) {
                            // 栈形 [this, name, bytes, off, len] 与合成静态方法签名完全一致，直接换调用目标
                            modified[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, HELPER_METHOD, HELPER_DESC, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (modified[0]) {
                    appendRedirectHelper(cv);
                }
                super.visitEnd();
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    /**
     * 追加合成方法：{@code static Class<?> ssoptimizer$defineClassRedirected(CoreLoader, String, byte[], int, int)}。
     * 先经 RenderThreadRedirector 改写字节码（长度可变），再以新缓冲回调原 defineClass。
     */
    private static void appendRedirectHelper(final ClassVisitor cv) {
        final MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HELPER_METHOD,
                HELPER_DESC,
                null,
                null);
        mv.visitCode();

        // byte[] redirected = RenderThreadRedirector.redirect(name, bytes)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECT_OWNER, "redirect", "(Ljava/lang/String;[B)[B", false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        // if (redirected == bytes) 走原参数；否则 bytes=redirected, off=0, len=redirected.length
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        final Label callOriginal = new Label();
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, callOriginal);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        // return self.defineClass(name, bytes, off, len)
        mv.visitLabel(callOriginal);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET_CLASS, "defineClass", DEFINE_CLASS_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
