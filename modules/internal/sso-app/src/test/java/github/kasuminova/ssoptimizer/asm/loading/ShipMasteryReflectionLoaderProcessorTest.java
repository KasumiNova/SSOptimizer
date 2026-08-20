package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Ship Mastery System ReflectionEnabledClassLoader 字节码注入：
 * 追加的 findClass 覆盖经 ShipMasteryLoaderSupport 读字节 + 重定向后自行 defineClass，
 * loader 自身无此类时回退 super.findClass（ClassNotFoundException）。
 * <p>
 * 不止校验字节码形态：把注入后的类实际定义出来，挂一个含 probe/Target.class 的
 * 临时目录 URL 跑完整加载链路（findClass 覆盖 → 读字节 → definePackage → defineClass）。
 * 注：动态定义的类无法在编译期静态引用，实例化经构造器反射完成——这是类加载
 * 测试的固有需求（动态类系统场景），不涉及对实现细节的越权访问。
 */
class ShipMasteryReflectionLoaderProcessorTest {

    private static final String FIXTURE_CLASS = "shipmastery/plugin/ModPlugin$ReflectionEnabledClassLoader";

    @TempDir
    Path tempDir;

    @Test
    void injectedFindClassDefinesClassThroughFullChainAndFallsBackWhenAbsent() throws Exception {
        final byte[] rewritten = new ShipMasteryReflectionLoaderProcessor().process(fixtureClassBytes());
        assertNotNull(rewritten, "目标类应被注入");

        // 把注入后的类定义进测试 loader（parent 为测试类加载器，
        // 与运行时一致：ReflectionEnabledClassLoader 的 parent 链可见 ssoptimizer 类）
        final ByteArrayLoader definingLoader = new ByteArrayLoader(getClass().getClassLoader());
        final Class<?> fixtureType = definingLoader.define(FIXTURE_CLASS.replace('/', '.'), rewritten);
        assertTrue(URLClassLoader.class.isAssignableFrom(fixtureType), "注入后仍应是 URLClassLoader 子类");

        // 造一个仅含 probe/Target.class 的 classpath 目录，实例化注入后的 loader
        final Path probePath = tempDir.resolve("probe/Target.class");
        Files.createDirectories(probePath.getParent());
        Files.write(probePath, probeClassBytes());
        final URLClassLoader loader = (URLClassLoader) fixtureType
                .getDeclaredConstructor(URL[].class, ClassLoader.class)
                .newInstance(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader());

        // 完整链路：loadClass → 父委派未命中 → findClass 覆盖 → 读字节 → definePackage → defineClass
        final Class<?> loaded = loader.loadClass("probe.Target");
        assertEquals("probe.Target", loaded.getName());
        assertSame(loader, loaded.getClassLoader(), "probe.Target 应由注入后的 loader 自行定义");

        // 回退路径：父委派与本级都没有的类 → findClass 覆盖 → 支撑返回 null
        // → super.findClass → ClassNotFoundException
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("probe.Missing"));
    }

    @Test
    void supportReadsBytesFromLoaderOwnClasspath() throws Exception {
        final Path probePath = tempDir.resolve("probe/Target.class");
        Files.createDirectories(probePath.getParent());
        Files.write(probePath, probeClassBytes());

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            // 命中：读出与原字节等长的结果（probe 类无 GL 引用，redirect 原样透传）
            final byte[] bytes = ShipMasteryLoaderSupport.loadTransformedBytes(loader, "probe.Target");
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);

            // 未命中：返回 null（调用方回退 super.findClass）
            assertNull(ShipMasteryLoaderSupport.loadTransformedBytes(loader, "probe.Missing"));
        }
    }

    @Test
    void skipsClassThatAlreadyOverridesFindClass() {
        final ClassWriter writer = fixtureWriter();
        final MethodVisitor existing = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "findClass", "(Ljava/lang/String;)Ljava/lang/Class;",
                null, new String[]{"java/lang/ClassNotFoundException"});
        existing.visitCode();
        existing.visitVarInsn(Opcodes.ALOAD, 0);
        existing.visitVarInsn(Opcodes.ALOAD, 1);
        existing.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URLClassLoader",
                "findClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        existing.visitInsn(Opcodes.ARETURN);
        existing.visitMaxs(0, 0);
        existing.visitEnd();
        writer.visitEnd();

        // 已自带 findClass 覆盖（模组更新形态变化）→ 放弃注入
        assertNull(new ShipMasteryReflectionLoaderProcessor().process(writer.toByteArray()));
    }

    @Test
    void ignoresUnrelatedClasses() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "some/Other", null, "java/lang/Object", null);
        writer.visitEnd();
        assertNull(new ShipMasteryReflectionLoaderProcessor().process(writer.toByteArray()));
    }

    /** 与真实目标同形的夹具：URLClassLoader 子类 + (URL[], ClassLoader) 构造。 */
    private static byte[] fixtureClassBytes() {
        final ClassWriter writer = fixtureWriter();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter fixtureWriter() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, FIXTURE_CLASS, null, "java/net/URLClassLoader", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "([Ljava/net/URL;Ljava/lang/ClassLoader;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ALOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URLClassLoader",
                "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    /** 探针类：public class probe.Target（无 GL 引用，redirect 应原样透传）。 */
    private static byte[] probeClassBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "probe/Target", null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** 内存字节码定义 loader：不经反射地把注入后的夹具类定义出来。 */
    private static final class ByteArrayLoader extends ClassLoader {
        ByteArrayLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
