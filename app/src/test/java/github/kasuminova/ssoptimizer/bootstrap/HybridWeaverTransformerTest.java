package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

class HybridWeaverTransformerTest {
    private static final TinyV2MappingRepository REPOSITORY = TinyV2MappingRepository.loadDefault();

    private static byte[] createNamedSpriteWithObfuscatedTextureField() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/fs/graphics/Sprite", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PROTECTED, "texture", "Lcom/fs/graphics/Object;", null, null).visitEnd();

        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "getTexture", "()Lcom/fs/graphics/Object;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "com/fs/graphics/Sprite", "texture", "Lcom/fs/graphics/Object;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    @Test
    void returnsNullForUnregisteredClass() {
        var transformer = new HybridWeaverTransformer();
        assertNull(transformer.transform(null, "com/example/Unknown", null, null, new byte[]{1}));
    }

    @Test
    void appliesRegisteredProcessor() {
        var transformer = new HybridWeaverTransformer();
        byte[] expected = {1, 2, 3};
        transformer.registerProcessor("com.example.Target", bytes -> expected);

        byte[] result = transformer.transform(null, "com/example/Target", null, null, new byte[0]);
        assertArrayEquals(expected, result);
    }

    @Test
    void exceptionFallsBackToOriginalBytecode() {
        var transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("com.example.Bad", bytes -> {
            throw new RuntimeException("boom");
        });

        assertNull(transformer.transform(null, "com/example/Bad", null, null, new byte[]{1}));
    }

    @Test
    void registerAndRemoveProcessorChangesCount() {
        var transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("com.example.Foo", bytes -> bytes);
        assertEquals(1, transformer.getProcessorCount());
        transformer.removeProcessor("com.example.Foo");
        assertEquals(0, transformer.getProcessorCount());
    }

    @Test
    void resolvesObfuscatedIncomingClassNameThroughMappings() {
        MappingEntry runtimeEntry = REPOSITORY.requireClassByNamedName("com/fs/starfarer/renderers/TexturedStripRenderer");
        var transformer = new HybridWeaverTransformer(TinyV2MappingRepository.of(java.util.List.of(
            MappingEntry.classEntry(runtimeEntry.obfuscatedName(), runtimeEntry.namedName())
        )));
        byte[] expected = {4, 5, 6};
        transformer.registerProcessor("com.fs.starfarer.renderers.TexturedStripRenderer", bytes -> expected);

        byte[] result = transformer.transform(null, runtimeEntry.obfuscatedName(), null, null, new byte[0]);
        assertArrayEquals(expected, result);
    }

    @Test
    void remapsDescriptorsBeforePassingNamedClassToProcessor() {
        var transformer = new HybridWeaverTransformer(TinyV2MappingRepository.of(java.util.List.of(
                MappingEntry.classEntry("com/fs/graphics/Object", "com/fs/graphics/TextureObject")
        )));
        transformer.registerProcessor("com.fs.graphics.Sprite", bytes -> bytes);

        byte[] result = transformer.transform(null,
                "com/fs/graphics/Sprite",
                null,
                null,
                createNamedSpriteWithObfuscatedTextureField());

        assertNotNull(result, "processor 命中后应返回传给它的字节码");

        ClassNode node = readClass(result);
        assertTrue(node.fields.stream().anyMatch(field -> "texture".equals(field.name)
                        && "Lcom/fs/graphics/TextureObject;".equals(field.desc)),
                "HybridWeaverTransformer 应先把字段描述符 remap 成 named，再交给 ASM processor");
        assertTrue(node.methods.stream().anyMatch(method -> "getTexture".equals(method.name)
                        && "()Lcom/fs/graphics/TextureObject;".equals(method.desc)),
                "HybridWeaverTransformer 也应同步 remap 方法描述符");
    }
}
