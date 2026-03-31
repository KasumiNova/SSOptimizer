package github.kasuminova.ssoptimizer.bootstrap;

import org.codehaus.janino.JavaSourceClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionSanitizingTransformerTest {

    private final ReflectionSanitizingTransformer transformer = new ReflectionSanitizingTransformer();

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        BootstrapSearchInstaller.resetForTest();
    }

    @Test
    void skipJavaClasses() {
        byte[] dummy = createDummyClass("java/lang/Foo");
        assertNull(transformer.transform(null, "java/lang/Foo", null, null, dummy));
    }

    @Test
    void skipOwnPackage() {
        byte[] dummy = createDummyClass("github/kasuminova/ssoptimizer/bootstrap/Foo");
        assertNull(transformer.transform(null, "github/kasuminova/ssoptimizer/bootstrap/Foo", null, null, dummy));
    }

    @Test
    void rewritesNonGameClassesWhenReflectionCallsExist() {
        BootstrapSearchInstaller.forceInstalledForTest();
        byte[] classBytes = createClassWithGetMethod("thirdparty/mod/MyModEntry");
        byte[] result = transformer.transform(null, "thirdparty/mod/MyModEntry", null, null, classBytes);

        assertNotNull(result, "Mod and third-party classes should be transformed once helper visibility is fixed");
        assertTrue(containsInvokeStatic(result, "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper", "getMethod"));
    }

    @Test
    void skipsNonGameClassesUntilHelperVisibilityIsReady() {
        byte[] classBytes = createClassWithGetMethod("thirdparty/mod/MyModEntry");
        assertNull(transformer.transform(null, "thirdparty/mod/MyModEntry", null, null, classBytes));
    }

    @Test
    void noReflectionCallsReturnsNull() {
        byte[] classBytes = createClassWithNormalCall("thirdparty/mod/NoReflection");
        assertNull(transformer.transform(null, "thirdparty/mod/NoReflection", null, null, classBytes));
    }

    @Test
    void skipsJaninoLoadedClassesEvenWhenHelperVisibilityIsReady() {
        BootstrapSearchInstaller.forceInstalledForTest();
        ClassLoader janinoLoader = new JavaSourceClassLoader(getClass().getClassLoader(), new File[0], null);
        byte[] classBytes = createClassWithGetMethod("thirdparty/mod/MyModEntry");
        assertNull(transformer.transform(janinoLoader, "thirdparty/mod/MyModEntry", null, null, classBytes));
    }

    @Test
    void rewritesGetMethodCall() {
        BootstrapSearchInstaller.forceInstalledForTest();
        byte[] classBytes = createClassWithGetMethod("com/fs/WithReflection");
        byte[] result = transformer.transform(null, "com/fs/WithReflection", null, null, classBytes);
        assertNotNull(result, "Should return modified bytes when getMethod call is present");
        // Verify the bytecode contains invokestatic to ReflectionHelper
        assertTrue(containsInvokeStatic(result, "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper", "getMethod"));
    }

    @Test
    void rewritesGetDeclaredFieldCall() {
        BootstrapSearchInstaller.forceInstalledForTest();
        byte[] classBytes = createClassWithGetDeclaredField("com/fs/WithField");
        byte[] result = transformer.transform(null, "com/fs/WithField", null, null, classBytes);
        assertNotNull(result);
        assertTrue(containsInvokeStatic(result, "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper", "getDeclaredField"));
    }

    @Test
    void rewritesGetDeclaredMethodCall() {
        BootstrapSearchInstaller.forceInstalledForTest();
        byte[] classBytes = createClassWithGetDeclaredMethod("com/fs/WithDeclaredMethod");
        byte[] result = transformer.transform(null, "com/fs/WithDeclaredMethod", null, null, classBytes);
        assertNotNull(result);
        assertTrue(containsInvokeStatic(result, "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper", "getDeclaredMethod"));
    }

    @Test
    void rewritesGetFieldCall() {
        BootstrapSearchInstaller.forceInstalledForTest();
        byte[] classBytes = createClassWithGetField("com/fs/WithPublicField");
        byte[] result = transformer.transform(null, "com/fs/WithPublicField", null, null, classBytes);
        assertNotNull(result);
        assertTrue(containsInvokeStatic(result, "github/kasuminova/ssoptimizer/bootstrap/ReflectionHelper", "getField"));
    }

    private byte[] createDummyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createClassWithNormalCall(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "test", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createClassWithGetMethod(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test",
                "()Ljava/lang/reflect/Method;", null, new String[]{"java/lang/Exception"});
        mv.visitCode();
        // Push String.class (any Class)
        mv.visitLdcInsn(Type.getType("Ljava/lang/String;"));
        // Push name
        mv.visitLdcInsn("if.new");
        // Push empty Class array
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        // Call getMethod
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createClassWithGetDeclaredField(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test",
                "()Ljava/lang/reflect/Field;", null, new String[]{"java/lang/Exception"});
        mv.visitCode();
        mv.visitLdcInsn(Type.getType("Ljava/lang/String;"));
        mv.visitLdcInsn("some.field");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createClassWithGetDeclaredMethod(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test",
                "()Ljava/lang/reflect/Method;", null, new String[]{"java/lang/Exception"});
        mv.visitCode();
        mv.visitLdcInsn(Type.getType("Ljava/lang/String;"));
        mv.visitLdcInsn("if.new");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createClassWithGetField(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test",
                "()Ljava/lang/reflect/Field;", null, new String[]{"java/lang/Exception"});
        mv.visitCode();
        mv.visitLdcInsn(Type.getType("Ljava/lang/String;"));
        mv.visitLdcInsn("if.new");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private boolean containsInvokeStatic(byte[] classBytes, String owner, String methodName) {
        boolean[] found = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals(o) && methodName.equals(n)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return found[0];
    }
}
