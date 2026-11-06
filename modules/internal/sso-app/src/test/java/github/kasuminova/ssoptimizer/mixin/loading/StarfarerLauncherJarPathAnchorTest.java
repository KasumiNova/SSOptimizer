package github.kasuminova.ssoptimizer.mixin.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StarfarerLauncherJarPathAnchorTest {

    @Test
    void launchGameHasExactlyOneGetJarFilesCall() throws IOException {
        final ClassNode node = readClasspathClass("com/fs/starfarer/StarfarerLauncher");

        MethodNode launchGame = null;
        for (final MethodNode method : node.methods) {
            if (method.name.equals("launchGame")
                    && method.desc.equals("(ZZLjava/lang/String;Ljava/lang/String;)V")) {
                launchGame = method;
                break;
            }
        }
        assertNotNull(launchGame, "StarfarerLauncher.launchGame(ZZLjava/lang/String;Ljava/lang/String;)V 必须存在");

        int callSites = 0;
        for (final var insn : launchGame.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("com/fs/starfarer/loading/scripts/ScriptStore")
                    && call.name.equals("getJarFiles")
                    && call.desc.equals("()Ljava/util/List;")) {
                callSites++;
            }
        }
        assertEquals(1, callSites, "launchGame 中 getJarFiles() 调用点必须恰好一个（redirect 锚点唯一性）");
    }

    private static ClassNode readClasspathClass(final String slashName) throws IOException {
        try (InputStream in = StarfarerLauncherJarPathAnchorTest.class.getClassLoader()
                .getResourceAsStream(slashName + ".class")) {
            assertNotNull(in, "测试 classpath 必须包含类: " + slashName);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
