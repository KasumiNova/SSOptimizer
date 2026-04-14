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
    private static final MappingLookup LOOKUP = new MappingLookup(TinyV2MappingRepository.loadDefault());

    @Test
    void remapsMethodNameAndDescriptorForRenamedOwnerClass() {
        BytecodeRemapper remapper = new BytecodeRemapper(
                TinyV2MappingRepository.loadDefault(),
                MappingDirection.OBFUSCATED_TO_NAMED);
    MappingEntry classEntry = LOOKUP.requireClassByNamedName("com/fs/graphics/font/BitmapFontManager");
    MappingEntry methodEntry = LOOKUP.requireMethodByNamedName(
        "com/fs/graphics/font/BitmapFontManager",
        "getFont",
        "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;");
    MappingEntry bitmapFontClassEntry = LOOKUP.requireClassByNamedName("com/fs/graphics/font/BitmapFont");

    BytecodeRemapper.RemappedClass remapped = remapper.remapClass(createObfuscatedBitmapFontManager(
        classEntry.obfuscatedName(),
        methodEntry.obfuscatedName(),
        "(Ljava/lang/String;)L" + bitmapFontClassEntry.obfuscatedName() + ";"));

        assertTrue(remapped.modified());
    assertEquals(classEntry.obfuscatedName(), remapped.inputInternalName());
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

    @Test
    void remapsSoundManagerPathMethodForRenamedOwnerClass() {
        BytecodeRemapper remapper = new BytecodeRemapper(
                TinyV2MappingRepository.loadDefault(),
                MappingDirection.OBFUSCATED_TO_NAMED);
        MappingEntry classEntry = LOOKUP.requireClassByNamedName("sound/SoundManager");
        MappingEntry methodEntry = LOOKUP.requireMethodByNamedName(
            "sound/SoundManager",
            "loadOAccentFamily",
            "(Ljava/lang/String;)Lsound/O0OO;");

        BytecodeRemapper.RemappedClass remapped = remapper.remapClass(createObfuscatedSoundManager(
            classEntry.obfuscatedName(),
            methodEntry.obfuscatedName(),
            methodEntry.descriptor()));

        assertTrue(remapped.modified());
        assertEquals(classEntry.obfuscatedName(), remapped.inputInternalName());
        assertEquals("sound/SoundManager", remapped.outputInternalName());

        final boolean[] foundNamedMethod = {false};
        new ClassReader(remapped.bytecode()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                if ("loadOAccentFamily".equals(name)
                        && "(Ljava/lang/String;)Lsound/O0OO;".equals(descriptor)) {
                    foundNamedMethod[0] = true;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        assertTrue(foundNamedMethod[0], "sound manager path loader should remap to the named method");
    }

    private static byte[] createObfuscatedBitmapFontManager(final String ownerObfuscatedName,
                                                            final String methodObfuscatedName,
                                                            final String methodDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                ownerObfuscatedName,
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
            methodObfuscatedName,
            methodDescriptor,
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

    private static byte[] createObfuscatedSoundManager(final String ownerObfuscatedName,
                                                       final String methodObfuscatedName,
                                                       final String methodDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                ownerObfuscatedName,
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
                Opcodes.ACC_PUBLIC,
            methodObfuscatedName,
            methodDescriptor,
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}