package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code shipmastery/plugin/ModPlugin$ReflectionEnabledClassLoader}。<br>
 * 注入位置：类结构级——追加 {@code findClass(String)} 覆盖与合成定义辅助方法。<br>
 * 注入动机：Ship Mastery System 的 ModPlugin 静态块创建 ReflectionEnabledClassLoader
 * （URLClassLoader 子类，parent 为模组加载器），其 {@code loadClass(String, boolean)}
 * 对 reflectionWhitelist 前缀（shipmastery.campaign / shipmastery.ui 等 13 项）直接调
 * {@code findClass} 自行 defineClass——绕过父委派，整条链路不经过 LaunchClassLoader
 * transformer 链；这些类（如 {@code FleetPanelHandler$FleetPanelItemUIPlugin.renderBelow}）
 * 的 lwjgl GL 调用在渲染线程分离模式下直奔真实 GL，主线程报
 * {@code No OpenGL context found in the current thread}。<br>
 * <p>
 * 为什么不用 Mixin：本改写是给 URLClassLoader 子类追加 {@code findClass} 覆盖与
 * 方法级 synchronized 定义辅助（自定义 defineClass 流程的结构级注入），不属于
 * Mixin 注入模型；且与 {@link AITweaksCoreLoaderProcessor} 同族的模组自建加载器
 * 适配惯例走 ASM processor（参 AGENTS.md「禁止 javaagent 模式」）。<br>
 * <p>
 * 注入效果：追加的 {@code findClass(String)} 先经 {@link ShipMasteryLoaderSupport}
 * 从 loader 自身读取类字节并过 {@code RenderThreadRedirector.redirect}
 * （非分离模式零开销原样返回），再调合成 synchronized 方法完成
 * definePackage + defineClass；loader 自身无此类时回退 {@code super.findClass}
 * （语义与原 URLClassLoader 一致：抛 ClassNotFoundException）。whitelist 内类的
 * 定义方不变（仍是 ReflectionEnabledClassLoader），模组的反射访问语义不受影响。<br>
 * <p>
 * 形态守卫：目标类须直接继承 {@code java/net/URLClassLoader} 且尚未自带
 * {@code findClass(String)} 覆盖；模组更新导致形态不符时放弃注入并 WARN
 * （宁可该类不改写，不产出非法类）。
 */
public final class ShipMasteryReflectionLoaderProcessor implements AsmClassProcessor {
    private static final Logger LOGGER = Logger.getLogger(ShipMasteryReflectionLoaderProcessor.class);

    public static final String TARGET_CLASS = "shipmastery/plugin/ModPlugin$ReflectionEnabledClassLoader";
    public static final String SUPER_CLASS = "java/net/URLClassLoader";
    public static final String FIND_CLASS_METHOD = "findClass";
    public static final String FIND_CLASS_DESC = "(Ljava/lang/String;)Ljava/lang/Class;";
    public static final String DEFINE_HELPER_METHOD = "ssoptimizer$defineTransformed";
    public static final String DEFINE_HELPER_DESC = "(Ljava/lang/String;[B)Ljava/lang/Class;";
    public static final String SUPPORT_OWNER = "github/kasuminova/ssoptimizer/asm/loading/ShipMasteryLoaderSupport";
    public static final String SUPPORT_METHOD = "loadTransformedBytes";
    public static final String SUPPORT_DESC = "(Ljava/net/URLClassLoader;Ljava/lang/String;)[B";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }
        if (!SUPER_CLASS.equals(reader.getSuperName())) {
            LOGGER.warn("[SSOptimizer] " + TARGET_CLASS + " 父类变为 " + reader.getSuperName()
                    + "，放弃注入（shipmastery 更新？渲染线程重定向对其 whitelist 类不再生效）");
            return null;
        }
        if (hasFindClassOverride(reader)) {
            LOGGER.warn("[SSOptimizer] " + TARGET_CLASS + " 已自带 findClass 覆盖，"
                    + "放弃注入（shipmastery 更新？渲染线程重定向对其 whitelist 类不再生效）");
            return null;
        }

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                appendFindClassOverride(cv);
                appendDefineHelper(cv);
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    /** 预扫描：目标类是否已存在同名同描述符的 findClass（避免重复定义产出非法类）。 */
    private static boolean hasFindClassOverride(final ClassReader reader) {
        final boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                if (FIND_CLASS_METHOD.equals(name) && FIND_CLASS_DESC.equals(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /**
     * 追加 {@code public Class<?> findClass(String) throws ClassNotFoundException}：
     * <pre>
     * byte[] bytes = ShipMasteryLoaderSupport.loadTransformedBytes(this, name);
     * if (bytes == null) return super.findClass(name);
     * return ssoptimizer$defineTransformed(name, bytes);
     * </pre>
     * 可见性必须 public：URLClassLoader.findClass 本身是 public，缩窄会在
     * 校验/链接期出问题；loadClass 内的 invokevirtual 调用点经多分派自动命中本覆盖。
     */
    private static void appendFindClassOverride(final ClassVisitor cv) {
        final MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC,
                FIND_CLASS_METHOD,
                FIND_CLASS_DESC,
                null,
                new String[]{"java/lang/ClassNotFoundException"});
        mv.visitCode();

        // byte[] bytes = ShipMasteryLoaderSupport.loadTransformedBytes(this, name)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT_OWNER, SUPPORT_METHOD, SUPPORT_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        // if (bytes != null) 走定义辅助；否则回退 super.findClass
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        final Label fallback = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET_CLASS, DEFINE_HELPER_METHOD, DEFINE_HELPER_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SUPER_CLASS, FIND_CLASS_METHOD, FIND_CLASS_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * 追加 {@code private synchronized Class<?> ssoptimizer$defineTransformed(String, byte[])}：
     * <pre>
     * int dot = name.lastIndexOf('.');
     * if (dot &gt; 0) {
     *     String pkg = name.substring(0, dot);
     *     if (getDefinedPackage(pkg) == null) definePackage(pkg, null×6, null);
     * }
     * return defineClass(name, bytes, 0, bytes.length);
     * </pre>
     * 方法级 synchronized 锁住 loader 实例：definePackage 对同包并发定义会抛
     * IllegalArgumentException，whitelist 类只允许经本方法串行定义。
     */
    private static void appendDefineHelper(final ClassVisitor cv) {
        final MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_SYNTHETIC,
                DEFINE_HELPER_METHOD,
                DEFINE_HELPER_DESC,
                null,
                null);
        mv.visitCode();

        // int dot = name.lastIndexOf('.')
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, '.');
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        // if (dot <= 0) 跳过包定义
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        final Label define = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, define);

        // String pkg = name.substring(0, dot)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        // if (getDefinedPackage(pkg) != null) 跳过包定义
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader",
                "getDefinedPackage", "(Ljava/lang/String;)Ljava/lang/Package;", false);
        mv.visitJumpInsn(Opcodes.IFNONNULL, define);

        // definePackage(pkg, null, null, null, null, null, null, null)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        for (int i = 0; i < 7; i++) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader",
                "definePackage",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/net/URL;)Ljava/lang/Package;",
                false);
        mv.visitInsn(Opcodes.POP);

        // return defineClass(name, bytes, 0, bytes.length)
        mv.visitLabel(define);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader",
                "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
