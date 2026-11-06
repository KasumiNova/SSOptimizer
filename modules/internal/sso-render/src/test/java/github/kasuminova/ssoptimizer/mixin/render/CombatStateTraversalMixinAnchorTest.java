package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatStateTraversalMixinAnchorTest {
    private static final String LWJGL_GL11_OWNER   = "org/lwjgl/opengl/GL11";
    private static final String BRIDGE_GL11_OWNER  = "github/kasuminova/ssoptimizer/bridge/opengl/GL11";
    private static final String FINISH_NAME        = "glFinish";
    private static final String FINISH_DESC        = "()V";

    @Test
    void combatStateTraverseContainsLwjglGlFinishCallSite() throws IOException {
        final ClassNode node = parse(readClasspathBytes(GameClassNames.COMBAT_STATE));

        final int callSites = countCallSites(findTraverse(node), LWJGL_GL11_OWNER);
        assertTrue(callSites >= 1,
                "CombatState.traverse 内必须存在 LWJGL GL11.glFinish()V 调用点（渲染线程关闭时的织入目标），实际 " + callSites);
    }

    @Test
    void renderThreadRedirectTurnsGlFinishIntoBridgeCallSite() throws IOException {
        final byte[] redirected = RenderThreadRedirector.redirect(
                GameClassNames.COMBAT_STATE, readClasspathBytes(GameClassNames.COMBAT_STATE));
        final ClassNode node = parse(redirected);
        final MethodNode traverse = findTraverse(node);

        final int bridgeCallSites = countCallSites(traverse, BRIDGE_GL11_OWNER);
        assertTrue(bridgeCallSites >= 1,
                "渲染线程重定向先于 Mixin 执行，traverse 内必须出现 bridge GL11.glFinish()V 调用点，实际 " + bridgeCallSites);
        assertTrue(countCallSites(traverse, LWJGL_GL11_OWNER) == 0,
                "重定向后 traverse 内不应残留 LWJGL GL11.glFinish()V 调用点");
    }

    private static MethodNode findTraverse(final ClassNode node) {
        for (final MethodNode method : node.methods) {
            if ("traverse".equals(method.name) && "()Ljava/lang/String;".equals(method.desc)) {
                return method;
            }
        }
        throw new AssertionError("CombatState.traverse()Ljava/lang/String; 必须存在（glFinish 锚点方法）");
    }

    private static int countCallSites(final MethodNode method, final String owner) {
        int callSites = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && FINISH_NAME.equals(call.name)
                    && FINISH_DESC.equals(call.desc)) {
                callSites++;
            }
        }
        return callSites;
    }

    private static ClassNode parse(final byte[] bytes) {
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] readClasspathBytes(final String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = CombatStateTraversalMixinAnchorTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含 " + resource);
            return in.readAllBytes();
        }
    }
}
