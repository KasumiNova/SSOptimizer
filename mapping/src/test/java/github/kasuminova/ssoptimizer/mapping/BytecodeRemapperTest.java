package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BytecodeRemapper} 在类名重命名场景下的方法 remap 回归测试。
 */
class BytecodeRemapperTest {

    @Test
    void remapsMethodNameAndDescriptorForRenamedOwnerClass() {
        BytecodeRemapper remapper = new BytecodeRemapper(
                TinyV2MappingRepository.loadDefault(),
                MappingDirection.OBFUSCATED_TO_NAMED);

        BytecodeRemapper.RemappedClass remapped = remapper.remapClass(createObfuscatedBitmapFontManager());

        assertTrue(remapped.modified());
        assertEquals("com/fs/graphics/super/D", remapped.inputInternalName());
        assertEquals("com/fs/graphics/font/BitmapFontManager", remapped.outputInternalName());

        ClassReader reader = new ClassReader(remapped.bytecode());
        assertEquals("com/fs/graphics/font/BitmapFontManager", reader.getClassName());

        boolean[] foundLookupMethod = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("getFont".equals(name)
                        && "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;".equals(descriptor)) {
                    foundLookupMethod[0] = true;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        assertTrue(foundLookupMethod[0], "renamed owner class should also remap its method name and descriptor");
    }

    private static byte[] createObfuscatedBitmapFontManager() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "com/fs/graphics/super/D",
                null,
                "java/lang/Object",
                null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "Ò00000",
                "(Ljava/lang/String;)Lcom/fs/graphics/super/return;",
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}