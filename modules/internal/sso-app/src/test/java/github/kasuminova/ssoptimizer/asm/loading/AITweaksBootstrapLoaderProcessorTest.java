package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AITweaksBootstrapLoaderProcessorTest {

    private static final String FACTORY_CLASS = AITweaksBootstrapLoaderProcessor.TARGET_CLASS + "$TestFactory";

    @Test
    void rewrittenLoaderUsesItsDefiningLoaderAsParent() throws Exception {
        final byte[] rewritten = new AITweaksBootstrapLoaderProcessor().process(targetClassBytes());
        assertNotNull(rewritten, "目标类应被改写");

        final DefiningLoader loader = new DefiningLoader();
        loader.define(AITweaksBootstrapLoaderProcessor.TARGET_CLASS, rewritten);
        loader.define(FACTORY_CLASS, factoryClassBytes());
        final LoaderFactory factory = github.kasuminova.ssoptimizer.testing.ClassInstances.create(
                LoaderFactory.class, loader, FACTORY_CLASS.replace('/', '.'));

        try (URLClassLoader instance = factory.create()) {
            assertSame(loader, instance.getParent(), "改写后 BootstrapLoader 的父加载器应为其定义类加载器");
        }
    }

    @Test
    void ignoresForeignClass() {
        assertNull(new AITweaksBootstrapLoaderProcessor().process(foreignClassBytes()),
                "非目标类不应被修改");
    }

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

    private static byte[] factoryClassBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                FACTORY_CLASS, null, "java/lang/Object", new String[]{Type.getInternalName(LoaderFactory.class)});
        appendObjectConstructor(writer);

        final MethodVisitor create = writer.visitMethod(Opcodes.ACC_PUBLIC, "create",
                "()Ljava/net/URLClassLoader;", null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, AITweaksBootstrapLoaderProcessor.TARGET_CLASS);
        create.visitInsn(Opcodes.DUP);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, AITweaksBootstrapLoaderProcessor.TARGET_CLASS,
                "<init>", "()V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void appendObjectConstructor(final ClassWriter writer) {
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    public interface LoaderFactory {
        URLClassLoader create();
    }

    private static final class DefiningLoader extends ClassLoader {
        Class<?> define(final String internalName, final byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
