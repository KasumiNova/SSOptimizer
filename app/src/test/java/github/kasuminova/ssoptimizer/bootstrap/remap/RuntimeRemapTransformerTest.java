package github.kasuminova.ssoptimizer.bootstrap.remap;

import github.kasuminova.ssoptimizer.bootstrap.RuntimeRemapTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行时重映射变换器测试。
 * <p>
 * 该测试锁定“先 remap 再 patch”的第一步：给定混淆类字节码时，变换器必须把
 * 类名、字段名和方法名翻译成 Tiny v2 中定义的可读命名，并且对重复输入保持幂等。
 */
class RuntimeRemapTransformerTest {

    private static byte[] createObfuscatedTextureLoader() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/fs/graphics/TextureLoader", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "a", "I", null, null).visitEnd();

        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "b", "(I)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 2);
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
    void remapsObfuscatedTextureLoaderToReadableNames() {
        RuntimeRemapTransformer transformer = new RuntimeRemapTransformer();
        byte[] transformed = transformer.transform(null, "com/fs/graphics/TextureLoader", null, null, createObfuscatedTextureLoader());

        assertNotNull(transformed, "重映射后的字节码不应为空");

        ClassNode node = readClass(transformed);
        assertEquals("com/fs/graphics/TextureLoader", node.name);
        assertTrue(node.fields.stream().anyMatch(field -> "cacheSize".equals(field.name)));
        assertTrue(node.methods.stream().anyMatch(method -> "reloadCache".equals(method.name)));
    }

    @Test
    void remapTransformerIsIdempotentForAlreadyNamedBytes() {
        RuntimeRemapTransformer transformer = new RuntimeRemapTransformer();
        byte[] first = transformer.transform(null, "com/fs/graphics/TextureLoader", null, null, createObfuscatedTextureLoader());

        assertNotNull(first);

        byte[] second = transformer.transform(null, "com/fs/graphics/TextureLoader", null, null, first);
        assertTrue(second == null || Arrays.equals(first, second), "重复重映射不应产生命名漂移");
    }
}