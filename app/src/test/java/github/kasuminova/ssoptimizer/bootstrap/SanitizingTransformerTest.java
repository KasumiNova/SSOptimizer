package github.kasuminova.ssoptimizer.bootstrap;

import org.codehaus.janino.JavaSourceClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class SanitizingTransformerTest {

    private byte[] createClassWithIllegalMethodName() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Obfuscated", null, "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "if.new", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(42);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createClassCallingIllegalMethod() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Caller", null, "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "callIt", "(Lcom/example/Obfuscated;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Obfuscated", "if.new", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void sanitizesIllegalMethodDefinition() {
        var transformer = new SanitizingTransformer();
        byte[] sanitized = transformer.transform(null, "com/example/Obfuscated", null, null, createClassWithIllegalMethodName());
        assertNotNull(sanitized, "Transformer should return sanitized bytes");

        ClassReader reader = new ClassReader(sanitized);
        boolean[] foundSanitized = {false};
        boolean[] foundIllegal = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ("if$dot$new".equals(name)) {
                    foundSanitized[0] = true;
                }
                if ("if.new".equals(name)) {
                    foundIllegal[0] = true;
                }
                return null;
            }
        }, 0);

        assertTrue(foundSanitized[0], "Sanitized method name should be present");
        assertFalse(foundIllegal[0], "Illegal method name should be gone");
    }

    @Test
    void sanitizesCallSiteReferences() {
        var transformer = new SanitizingTransformer();
        byte[] sanitized = transformer.transform(null, "com/example/Caller", null, null, createClassCallingIllegalMethod());
        assertNotNull(sanitized);

        ClassReader reader = new ClassReader(sanitized);
        boolean[] foundSanitizedCall = {false};
        boolean[] foundIllegalCall = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if ("if$dot$new".equals(methodName)) {
                            foundSanitizedCall[0] = true;
                        }
                        if ("if.new".equals(methodName)) {
                            foundIllegalCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundSanitizedCall[0], "Call site should reference sanitized name");
        assertFalse(foundIllegalCall[0], "Call site should not reference illegal name");
    }

    @Test
    void returnsNullForLegalClass() {
        var transformer = new SanitizingTransformer();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Legal", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "normalMethod", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();

        assertNull(transformer.transform(null, "com/example/Legal", null, null, cw.toByteArray()));
    }

    @Test
    void skipsJdkClasses() {
        assertNull(new SanitizingTransformer().transform(null, "java/lang/String", null, null, new byte[0]));
    }

    @Test
    void skipsOwnClasses() {
        assertNull(new SanitizingTransformer().transform(null, "github/kasuminova/ssoptimizer/Foo", null, null, new byte[0]));
    }

    @Test
    void skipsJaninoLoadedClasses() {
        ClassLoader janinoLoader = new JavaSourceClassLoader(getClass().getClassLoader(), new File[0], null);
        assertNull(new SanitizingTransformer().transform(janinoLoader, "thirdparty/mod/ScriptLike", null, null, createClassWithIllegalMethodName()));
    }

    @Test
    void sanitizedBytecodeIsLoadable() throws Exception {
        var transformer = new SanitizingTransformer();
        byte[] sanitized = transformer.transform(null, "com/example/Obfuscated", null, null, createClassWithIllegalMethodName());
        assertNotNull(sanitized);

        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> defineIt(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> clazz = loader.defineIt("com.example.Obfuscated", sanitized);
        assertNotNull(clazz);
        assertEquals("com.example.Obfuscated", clazz.getName());

        var method = clazz.getMethod("if$dot$new");
        assertNotNull(method);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        assertEquals(42, method.invoke(instance));
    }

    @Test
    void originalIllegalBytecodeFailsToLoad() {
        byte[] illegal = createClassWithIllegalMethodName();
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> defineIt(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        assertThrows(ClassFormatError.class,
                () -> loader.defineIt("com.example.Obfuscated", illegal),
                "JDK should reject illegal method names in defineClass");
    }

    @Test
    void gracefullyHandlesMalformedInput() {
        var transformer = new SanitizingTransformer();
        assertNull(transformer.transform(null, "com/example/Bad", null, null, new byte[]{0, 1, 2, 3}));
    }

    @Test
    void initMethodPreserved() {
        var transformer = new SanitizingTransformer();
        byte[] original = createClassWithIllegalMethodName();
        byte[] sanitized = transformer.transform(null, "com/example/Obfuscated", null, null, original);
        assertNotNull(sanitized);

        ClassReader reader = new ClassReader(sanitized);
        boolean[] foundInit = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ("<init>".equals(name)) {
                    foundInit[0] = true;
                }
                return null;
            }
        }, 0);

        assertTrue(foundInit[0], "<init> should be preserved");
    }
}
