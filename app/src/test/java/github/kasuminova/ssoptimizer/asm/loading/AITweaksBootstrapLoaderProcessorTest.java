package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AITweaks BootstrapLoader 父加载器注入：单参 URLClassLoader 构造调用点
 * 改写为双参构造（父加载器 = 自身定义类加载器），并实际加载改写后的类实例化，
 * 断言父加载器确实易主。
 */
class AITweaksBootstrapLoaderProcessorTest {
    @Test
    void rewritesConstructorToDualArgWithOwnClassLoader() throws Exception {
        final byte[] rewritten = new AITweaksBootstrapLoaderProcessor().process(targetClassBytes());
        assertNotNull(rewritten, "目标类应被注入");

        final boolean[] dualArgCall = {false};
        final boolean[] parentPushed = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                if (!"<init>".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private boolean sawLdcSelf;
                    private boolean sawGetClassLoader;

                    @Override
                    public void visitLdcInsn(final Object value) {
                        if (value instanceof Type type
                                && AITweaksBootstrapLoaderProcessor.TARGET_CLASS.equals(type.getInternalName())) {
                            sawLdcSelf = true;
                        }
                    }

                    @Override
                    public void visitMethodInsn(final int opcode,
                                                final String owner,
                                                final String methodName,
                                                final String methodDescriptor,
                                                final boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "java/lang/Class".equals(owner)
                                && "getClassLoader".equals(methodName)) {
                            sawGetClassLoader = true;
                        }
                        if (opcode == Opcodes.INVOKESPECIAL
                                && AITweaksBootstrapLoaderProcessor.SUPER_OWNER.equals(owner)
                                && "<init>".equals(methodName)) {
                            if (AITweaksBootstrapLoaderProcessor.SINGLE_ARG_DESC.equals(methodDescriptor)) {
                                throw new AssertionError("不应残留单参 URLClassLoader 构造调用");
                            }
                            if (AITweaksBootstrapLoaderProcessor.DUAL_ARG_DESC.equals(methodDescriptor)) {
                                dualArgCall[0] = true;
                                parentPushed[0] = sawLdcSelf && sawGetClassLoader;
                            }
                        }
                    }
                };
            }
        }, 0);

        assertTrue(dualArgCall[0], "构造调用点应改写为双参 URLClassLoader 构造");
        assertTrue(parentPushed[0], "双参构造前应以自身类加载器作为父加载器实参");

        // 行为验证：加载改写后的类并实例化，父加载器必须等于其定义类加载器
        final class DefiningLoader extends ClassLoader {
            Class<?> define() {
                return defineClass(AITweaksBootstrapLoaderProcessor.TARGET_CLASS.replace('/', '.'),
                        rewritten, 0, rewritten.length);
            }
        }
        final DefiningLoader loader = new DefiningLoader();
        final Object instance = loader.define().getDeclaredConstructor().newInstance();
        assertSame(loader, ((URLClassLoader) instance).getParent(),
                "改写后 BootstrapLoader 的父加载器应为其定义类加载器");
    }

    @Test
    void ignoresForeignClass() {
        assertNull(new AITweaksBootstrapLoaderProcessor().process(foreignClassBytes()),
                "非目标类不应被修改");
    }

    /** 合成与真实 BootstrapLoader 同名的类：构造内调单参 URLClassLoader.<init>。 */
    private static byte[] targetClassBytes() {
        return bootstrapLoaderLikeBytes(AITweaksBootstrapLoaderProcessor.TARGET_CLASS);
    }

    private static byte[] foreignClassBytes() {
        return bootstrapLoaderLikeBytes("com/genir/aitweaks/launcher/loading/NotBootstrapLoader");
    }

    private static byte[] bootstrapLoaderLikeBytes(final String internalName) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null,
                AITweaksBootstrapLoaderProcessor.SUPER_OWNER, null);

        // public X() { super(new java.net.URL[0]); }
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitInsn(Opcodes.ICONST_0);
        constructor.visitTypeInsn(Opcodes.ANEWARRAY, "java/net/URL");
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, AITweaksBootstrapLoaderProcessor.SUPER_OWNER,
                "<init>", AITweaksBootstrapLoaderProcessor.SINGLE_ARG_DESC, false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
