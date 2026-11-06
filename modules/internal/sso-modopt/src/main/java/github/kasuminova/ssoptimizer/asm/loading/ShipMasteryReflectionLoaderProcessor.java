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
 * 为 Ship Mastery 自建类加载器追加 findClass 与同步定义辅助，使白名单类经过渲染重定向。
 * <p>该改写新增方法并自定义 defineClass 流程，Mixin 无法完成此类结构修改。</p>
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

    /** 覆盖方法必须保持 public，以匹配 URLClassLoader.findClass 的可见性。 */
    private static void appendFindClassOverride(final ClassVisitor cv) {
        final MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC,
                FIND_CLASS_METHOD,
                FIND_CLASS_DESC,
                null,
                new String[]{"java/lang/ClassNotFoundException"});
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT_OWNER, SUPPORT_METHOD, SUPPORT_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

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

    /** 同步定义辅助串行化 definePackage，避免同包并发定义抛出 IllegalArgumentException。 */
    private static void appendDefineHelper(final ClassVisitor cv) {
        final MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_SYNTHETIC,
                DEFINE_HELPER_METHOD,
                DEFINE_HELPER_DESC,
                null,
                null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, '.');
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        mv.visitVarInsn(Opcodes.ILOAD, 3);
        final Label define = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, define);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader",
                "getDefinedPackage", "(Ljava/lang/String;)Ljava/lang/Package;", false);
        mv.visitJumpInsn(Opcodes.IFNONNULL, define);

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
