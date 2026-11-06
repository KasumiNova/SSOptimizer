package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ResourceLoaderOpenStreamFallbackMixinAnchorTest {
    private static final String OPEN_STREAM_DESC   = "(Ljava/lang/String;)Ljava/io/InputStream;";
    private static final String OPEN_RESOURCE_DESC = "(Ljava/lang/String;Z)Ljava/io/InputStream;";

    @Test
    void openStreamHasSingleOpenResourceCallSite() throws IOException {
        final ClassNode node = new ClassNode();
        try (InputStream in = ResourceLoaderOpenStreamFallbackMixinAnchorTest.class.getClassLoader()
                .getResourceAsStream(GameClassNames.RESOURCE_LOADER + ".class")) {
            assertNotNull(in, "测试 classpath 必须包含 ResourceLoader");
            new ClassReader(in).accept(node, 0);
        }

        MethodNode openStream = null;
        for (final MethodNode method : node.methods) {
            if (GameMemberNames.ResourceLoader.OPEN_STREAM.equals(method.name) && OPEN_STREAM_DESC.equals(method.desc)) {
                openStream = method;
                break;
            }
        }
        assertNotNull(openStream, "ResourceLoader.openStream(String) 必须存在");

        int callSites = 0;
        for (AbstractInsnNode insn = openStream.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && GameClassNames.RESOURCE_LOADER.equals(call.owner)
                    && "openResource".equals(call.name)
                    && OPEN_RESOURCE_DESC.equals(call.desc)) {
                callSites++;
            }
        }
        assertEquals(1, callSites, "openStream 内必须恰有一个 openResource(String,Z) 调用点");
    }
}
