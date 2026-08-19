package github.kasuminova.ssoptimizer.asm.font;

import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitmapFontManagerSyncProcessorTest {
    @Test
    void addsSynchronizedToFontEntryPoints() throws Exception {
        byte[] rewritten = new BitmapFontManagerSyncProcessor().process(createFakeFontManagerClass());
        assertNotNull(rewritten, "target class should be rewritten");

        Map<String, Integer> accessByName = inspectAccess(rewritten);
        assertTrue((accessByName.get(GameMemberNames.BitmapFontManager.GET_FONT) & Opcodes.ACC_SYNCHRONIZED) != 0,
                "getFont must become synchronized");
        assertTrue((accessByName.get(GameMemberNames.BitmapFontManager.LOAD_FONT) & Opcodes.ACC_SYNCHRONIZED) != 0,
                "loadFont must become synchronized");
        assertTrue((accessByName.get("tokenizeLine") & Opcodes.ACC_SYNCHRONIZED) == 0,
                "private helpers must stay unsynchronized");
        assertTrue((accessByName.get("getFontCount") & Opcodes.ACC_SYNCHRONIZED) == 0,
                "unrelated static methods must stay unsynchronized");

        // 改写后可加载并真实并发调用，验证 synchronized 语义生效且字节码可校验。
        Class<?> managerClass = new ByteArrayClassLoader().define(
                BitmapFontManagerSyncProcessor.TARGET_CLASS.replace('/', '.'), rewritten);
        int threads = 8;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final String key = "font" + i;
            workers[i] = new Thread(() -> {
                for (int round = 0; round < 200; round++) {
                    try {
                        managerClass.getMethod(GameMemberNames.BitmapFontManager.GET_FONT, String.class)
                                .invoke(null, key);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    @Test
    void ignoresNonTargetClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Unrelated", null, "java/lang/Object", null);
        cw.visitEnd();
        assertNull(new BitmapFontManagerSyncProcessor().process(cw.toByteArray()));
    }

    @Test
    void leavesAlreadySynchronizedMethodsUntouched() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, BitmapFontManagerSyncProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                GameMemberNames.BitmapFontManager.GET_FONT,
                "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        byte[] rewritten = new BitmapFontManagerSyncProcessor().process(cw.toByteArray());
        assertNull(rewritten, "already synchronized entries must not be rewritten");
    }

    private byte[] createFakeFontManagerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, BitmapFontManagerSyncProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "fonts", "Ljava/util/HashMap;", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, BitmapFontManagerSyncProcessor.TARGET_CLASS, "fonts", "Ljava/util/HashMap;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();

        MethodVisitor getFont = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                GameMemberNames.BitmapFontManager.GET_FONT,
                "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;", null, null);
        getFont.visitCode();
        getFont.visitFieldInsn(Opcodes.GETSTATIC, BitmapFontManagerSyncProcessor.TARGET_CLASS, "fonts", "Ljava/util/HashMap;");
        getFont.visitVarInsn(Opcodes.ALOAD, 0);
        getFont.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        getFont.visitTypeInsn(Opcodes.CHECKCAST, "com/fs/graphics/font/BitmapFont");
        getFont.visitInsn(Opcodes.ARETURN);
        getFont.visitMaxs(2, 1);
        getFont.visitEnd();

        MethodVisitor loadFont = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                GameMemberNames.BitmapFontManager.LOAD_FONT,
                "(Ljava/lang/String;Ljava/lang/String;)V", null, new String[]{"java/io/IOException"});
        loadFont.visitCode();
        loadFont.visitFieldInsn(Opcodes.GETSTATIC, BitmapFontManagerSyncProcessor.TARGET_CLASS, "fonts", "Ljava/util/HashMap;");
        loadFont.visitVarInsn(Opcodes.ALOAD, 0);
        loadFont.visitInsn(Opcodes.ACONST_NULL);
        loadFont.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        loadFont.visitInsn(Opcodes.POP);
        loadFont.visitInsn(Opcodes.RETURN);
        loadFont.visitMaxs(3, 2);
        loadFont.visitEnd();

        MethodVisitor tokenize = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "tokenizeLine", "(Ljava/lang/String;)V", null, null);
        tokenize.visitCode();
        tokenize.visitInsn(Opcodes.RETURN);
        tokenize.visitMaxs(0, 1);
        tokenize.visitEnd();

        MethodVisitor count = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getFontCount", "()I", null, null);
        count.visitCode();
        count.visitFieldInsn(Opcodes.GETSTATIC, BitmapFontManagerSyncProcessor.TARGET_CLASS, "fonts", "Ljava/util/HashMap;");
        count.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "size", "()I", false);
        count.visitInsn(Opcodes.IRETURN);
        count.visitMaxs(1, 0);
        count.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private Map<String, Integer> inspectAccess(byte[] classBytes) {
        Map<String, Integer> accessByName = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                accessByName.put(name, access);
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return accessByName;
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            Class<?> defined = defineClass(name, bytes, 0, bytes.length);
            assertFalse(defined.isInterface());
            return defined;
        }
    }
}
