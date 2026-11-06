package github.kasuminova.ssoptimizer.mixin.debug;

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

class CombatStateMainThreadDrainMixinAnchorTest {

    private static final String ANCHOR_OWNER = "com/fs/starfarer/SoundManager";
    private static final String ANCHOR_DESC  = "(FFFFFI)V";

    @Test
    void combatStateTraverseContainsSoundManagerAdvanceCallSite() throws IOException {
        final ClassNode node = readClasspathClass(GameClassNames.COMBAT_STATE);

        MethodNode traverse = null;
        for (final MethodNode method : node.methods) {
            if ("traverse".equals(method.name) && "()Ljava/lang/String;".equals(method.desc)) {
                traverse = method;
                break;
            }
        }
        assertNotNull(traverse, "CombatState.traverse()Ljava/lang/String; 必须存在（drain 锚点方法）");

        int callSites = 0;
        for (AbstractInsnNode insn = traverse.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                final MethodInsnNode call = (MethodInsnNode) insn;
                if (ANCHOR_OWNER.equals(call.owner) && "advance".equals(call.name)
                        && ANCHOR_DESC.equals(call.desc)) {
                    callSites++;
                }
            }
        }
        assertTrue(callSites >= 1,
                "CombatState.traverse 内必须存在 SoundManager.advance(FFFFFI)V 调用点，实际 " + callSites);
    }

    private static ClassNode readClasspathClass(final String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = CombatStateMainThreadDrainMixinAnchorTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含 " + resource);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
