package github.kasuminova.ssoptimizer.modopt.dcr;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DcrOnGameLoadProcessor} 结构验证（基于编译好的 plugin 夹具）：onGameLoad 中
 * {@code saveCombatResult} 调用已 redirect 为 {@code ssoptimizer$collect}，且每个 RETURN 前注入了
 * {@code ssoptimizer$flush}。无反射。
 */
class DcrOnGameLoadProcessorTest {

    private static final String SM = "data/scripts/combatanalytics/SerializationManager";

    @Test
    void redirectsSaveCombatResultToCollect() throws IOException {
        final MethodNode onGameLoad = transformedOnGameLoad();
        long saveCalls = countStaticCalls(onGameLoad, SM, "saveCombatResult");
        long collectCalls = countStaticCalls(onGameLoad, SM, "ssoptimizer$collect");
        assertEquals(0, saveCalls, "原 saveCombatResult 调用应全部被 redirect");
        assertEquals(1, collectCalls, "应恰有一处 ssoptimizer$collect");
    }

    @Test
    void injectsFlushBeforeEveryReturn() throws IOException {
        final MethodNode onGameLoad = transformedOnGameLoad();
        final AbstractInsnNode[] insns = onGameLoad.instructions.toArray();
        int returns = 0;
        int flushBeforeReturn = 0;
        for (int i = 0; i < insns.length; i++) {
            if (insns[i].getOpcode() == Opcodes.RETURN) {
                returns++;
                final AbstractInsnNode prev = previousReal(insns, i);
                if (prev instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(SM) && mi.name.equals("ssoptimizer$flush")) {
                    flushBeforeReturn++;
                }
            }
        }
        assertTrue(returns >= 1, "onGameLoad 应至少一个 return");
        assertEquals(returns, flushBeforeReturn, "每个 return 前都应注入 flush");
    }

    @Test
    void ignoresNonTargetClass() {
        final byte[] foreign = readClassBytes(DcrOnGameLoadProcessorTest.class.getName());
        assertNull(new DcrOnGameLoadProcessor().process(foreign));
    }

    private MethodNode transformedOnGameLoad() throws IOException {
        final byte[] original = readClassBytes("data.scripts.combatanalytics.DetailedCombatResultsModPlugin");
        final byte[] transformed = new DcrOnGameLoadProcessor().process(original);
        assertNotNull(transformed, "plugin 应被处理器修改");
        final ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        return node.methods.stream()
                .filter(m -> m.name.equals("onGameLoad") && m.desc.equals("(Z)V"))
                .findFirst().orElseThrow();
    }

    private static long countStaticCalls(final MethodNode m, final String owner, final String name) {
        return java.util.Arrays.stream(m.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(owner) && mi.name.equals(name))
                .count();
    }

    private static AbstractInsnNode previousReal(final AbstractInsnNode[] insns, final int from) {
        for (int i = from - 1; i >= 0; i--) {
            if (insns[i].getOpcode() >= 0) {
                return insns[i];
            }
        }
        return null;
    }

    private static byte[] readClassBytes(final String binaryName) {
        final String resource = "/" + binaryName.replace('.', '/') + ".class";
        try (InputStream in = DcrOnGameLoadProcessorTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "找不到类资源: " + resource);
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
