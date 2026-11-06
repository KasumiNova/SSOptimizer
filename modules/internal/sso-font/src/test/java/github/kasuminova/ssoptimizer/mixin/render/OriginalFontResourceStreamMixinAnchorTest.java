package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OriginalFontResourceStreamMixinAnchorTest {

    private static final String OPEN_STREAM_DESC = "(Ljava/lang/String;)Ljava/io/InputStream;";

    @Test
    void resourceLoaderContainsOpenStreamHeadInjectionTarget() throws IOException {
        final ClassNode node = readClasspathClass(GameClassNames.RESOURCE_LOADER);
        assertFalse((node.access & Opcodes.ACC_INTERFACE) != 0,
                "ResourceLoader 必须是可作为 Mixin 目标的类");

        MethodNode openStream = null;
        for (final MethodNode method : node.methods) {
            if (GameMemberNames.ResourceLoader.OPEN_STREAM.equals(method.name)
                    && OPEN_STREAM_DESC.equals(method.desc)) {
                openStream = method;
                break;
            }
        }
        assertNotNull(openStream, "ResourceLoader.openStream(String) 必须存在");
        assertFalse((openStream.access & Opcodes.ACC_STATIC) != 0,
                "openStream 必须为实例方法，以匹配 Mixin 回调签名");
    }

    private static ClassNode readClasspathClass(final String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = OriginalFontResourceStreamMixinAnchorTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含 " + resource);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
