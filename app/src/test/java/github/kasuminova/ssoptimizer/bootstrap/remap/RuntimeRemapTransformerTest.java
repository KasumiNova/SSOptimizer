package github.kasuminova.ssoptimizer.bootstrap.remap;

import github.kasuminova.ssoptimizer.bootstrap.RuntimeRemapTransformer;
import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
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
    private static final String                  SPRITE_CLASS         = "com/fs/graphics/Sprite";
    private static final String                  TEXTURE_LOADER_CLASS = "com/fs/graphics/TextureLoader";
    private static final String                  TEXTURE_OBJECT_CLASS = "com/fs/graphics/TextureObject";
    private static final TinyV2MappingRepository REPOSITORY           = TinyV2MappingRepository.loadDefault();

    private static byte[] createNamedSpriteWithObfuscatedTextureField() {
        String obfuscatedTextureObjectClass = REPOSITORY.requireClassByNamedName(TEXTURE_OBJECT_CLASS).obfuscatedName();

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, SPRITE_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PROTECTED, "texture", "L" + obfuscatedTextureObjectClass + ";", null, null).visitEnd();

        var getter = writer.visitMethod(Opcodes.ACC_PUBLIC, "getTexture", "()L" + obfuscatedTextureObjectClass + ";", null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, SPRITE_CLASS, "texture", "L" + obfuscatedTextureObjectClass + ";");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createObfuscatedTextureLoader() {
        MappingEntry textureLoaderClass = REPOSITORY.requireClassByNamedName(TEXTURE_LOADER_CLASS);
        MappingEntry cacheSizeField = REPOSITORY.requireFieldByNamedName(TEXTURE_LOADER_CLASS, "cacheSize");
        MappingEntry reloadCacheMethod = REPOSITORY.requireMethodByNamedName(TEXTURE_LOADER_CLASS, "reloadCache", "(I)V");
        MappingEntry loadTextureMethod = REPOSITORY.requireMethodByNamedName(
                TEXTURE_LOADER_CLASS,
                "loadTexture",
                "(Ljava/lang/String;)Lcom/fs/graphics/TextureObject;");

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, textureLoaderClass.obfuscatedName(), null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, cacheSizeField.obfuscatedName(), "I", null, null).visitEnd();

        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, reloadCacheMethod.obfuscatedName(), reloadCacheMethod.descriptor(), null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 2);
        method.visitEnd();

        var loadTexture = writer.visitMethod(Opcodes.ACC_PUBLIC, loadTextureMethod.obfuscatedName(), loadTextureMethod.descriptor(), null, null);
        loadTexture.visitCode();
        loadTexture.visitInsn(Opcodes.ACONST_NULL);
        loadTexture.visitInsn(Opcodes.ARETURN);
        loadTexture.visitMaxs(0, 2);
        loadTexture.visitEnd();

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
        MappingEntry textureLoaderClass = REPOSITORY.requireClassByNamedName(TEXTURE_LOADER_CLASS);
        MappingEntry cacheSizeField = REPOSITORY.requireFieldByNamedName(TEXTURE_LOADER_CLASS, "cacheSize");
        MappingEntry reloadCacheMethod = REPOSITORY.requireMethodByNamedName(TEXTURE_LOADER_CLASS, "reloadCache", "(I)V");

        RuntimeRemapTransformer transformer = new RuntimeRemapTransformer();
        byte[] transformed = transformer.transform(null, textureLoaderClass.obfuscatedName(), null, null, createObfuscatedTextureLoader());

        assertNotNull(transformed, "重映射后的字节码不应为空");

        ClassNode node = readClass(transformed);
        assertEquals(textureLoaderClass.namedName(), node.name);
        assertTrue(node.fields.stream().anyMatch(field -> cacheSizeField.namedName().equals(field.name)));
        assertTrue(node.methods.stream().anyMatch(method -> reloadCacheMethod.namedName().equals(method.name)));
    }

    @Test
    void remapTransformerIsIdempotentForAlreadyNamedBytes() {
        MappingEntry textureLoaderClass = REPOSITORY.requireClassByNamedName(TEXTURE_LOADER_CLASS);

        RuntimeRemapTransformer transformer = new RuntimeRemapTransformer();
        byte[] first = transformer.transform(null, textureLoaderClass.obfuscatedName(), null, null, createObfuscatedTextureLoader());

        assertNotNull(first);

        byte[] second = transformer.transform(null, textureLoaderClass.namedName(), null, null, first);
        assertTrue(second == null || Arrays.equals(first, second), "重复重映射不应产生命名漂移");
    }

    @Test
    void remapsMethodDescriptorWhenClassNameStaysReadable() {
        MappingEntry textureLoaderClass = REPOSITORY.requireClassByNamedName(TEXTURE_LOADER_CLASS);
        MappingEntry loadTextureMethod = REPOSITORY.requireMethodByNamedName(
                TEXTURE_LOADER_CLASS,
                "loadTexture",
                "(Ljava/lang/String;)Lcom/fs/graphics/TextureObject;");
        MappingEntry textureObjectClass = REPOSITORY.requireClassByNamedName(TEXTURE_OBJECT_CLASS);

        RuntimeRemapTransformer transformer = new RuntimeRemapTransformer();
        byte[] transformed = transformer.transform(null, textureLoaderClass.obfuscatedName(), null, null, createObfuscatedTextureLoader());

        assertNotNull(transformed, "TextureLoader 类名即便不变，只要方法描述符含映射类型也必须被重映射");

        ClassNode node = readClass(transformed);
        assertEquals(textureLoaderClass.namedName(), node.name);
        assertTrue(node.methods.stream().anyMatch(method -> loadTextureMethod.namedName().equals(method.name)
                        && ("(Ljava/lang/String;)L" + textureObjectClass.namedName() + ";").equals(method.desc)),
                "同名类中的方法描述符也应当被 remap 成 TextureObject");
    }

    @Test
    void remapsFieldDescriptorWhenClassNameStaysReadable() {
        RuntimeRemapTransformer transformer = new RuntimeRemapTransformer();
        byte[] transformed = transformer.transform(null, SPRITE_CLASS, null, null, createNamedSpriteWithObfuscatedTextureField());

        assertNotNull(transformed, "Sprite 类名即便已是 named，只要字段描述符仍是混淆态，也必须被重映射");

        ClassNode node = readClass(transformed);
        assertEquals(SPRITE_CLASS, node.name);
        assertTrue(node.fields.stream().anyMatch(field -> "texture".equals(field.name)
                        && ("L" + TEXTURE_OBJECT_CLASS + ";").equals(field.desc)),
                "同名类中的字段描述符也应当被 remap 成 TextureObject");
        assertTrue(node.methods.stream().anyMatch(method -> "getTexture".equals(method.name)
                        && ("()L" + TEXTURE_OBJECT_CLASS + ";").equals(method.desc)),
                "访问该字段的方法描述符也应同步 remap");
    }
}