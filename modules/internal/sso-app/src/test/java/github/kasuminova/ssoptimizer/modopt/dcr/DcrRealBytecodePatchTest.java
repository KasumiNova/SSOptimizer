package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcrRealBytecodePatchTest {

    private static final String SM = "data/scripts/combatanalytics/SerializationManager";
    private static final String PLUGIN = "data/scripts/combatanalytics/DetailedCombatResultsModPlugin";

    @Test
    void synthProcessorPatchesRealSerializationManager() throws IOException {
        final ClassNode node = patch(SM, new DcrBatchSaveSynthProcessor());
        assertNotNull(method(node, "ssoptimizer$collect", "(Ldata/scripts/combatanalytics/data/CombatResult;)V"),
                "真实 SerializationManager 应被注入 ssoptimizer$collect");
        assertNotNull(method(node, "ssoptimizer$flush", "()V"),
                "真实 SerializationManager 应被注入 ssoptimizer$flush");
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("ssoptimizer$dirty")),
                "真实 SerializationManager 应被注入 ssoptimizer$dirty");
    }

    @Test
    void onGameLoadProcessorPatchesRealPlugin() throws IOException {
        final ClassNode node = patch(PLUGIN, new DcrOnGameLoadProcessor());
        final MethodNode onGameLoad = method(node, "onGameLoad", "(Z)V");
        assertNotNull(onGameLoad, "真实 plugin 应有 onGameLoad(Z)V");
        assertEquals(0, staticCalls(onGameLoad, SM, "saveCombatResult"),
                "真实 onGameLoad 的 saveCombatResult 应全部被 redirect");
        assertEquals(1, staticCalls(onGameLoad, SM, "ssoptimizer$collect"),
                "真实 onGameLoad 应恰有一处 ssoptimizer$collect");
        assertTrue(staticCalls(onGameLoad, SM, "ssoptimizer$flush") >= 1,
                "真实 onGameLoad 应注入 ssoptimizer$flush");
    }

    private static ClassNode patch(final String internalName, final AsmClassProcessor processor) throws IOException {
        final byte[] original = readVendoredClass(internalName);
        final byte[] transformed = processor.process(original);
        assertNotNull(transformed, "处理器应匹配并修改真实类: " + internalName);
        new ClassReader(transformed).accept(new CheckClassAdapter(new ClassNode(), false), 0);
        final ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        return node;
    }

    private static byte[] readVendoredClass(final String internalName) throws IOException {
        final String resource = "/dcr/" + internalName + ".class";
        try (InputStream in = DcrRealBytecodePatchTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "缺少 vendored 的真实 DCR 类字节: " + resource
                    + "（见 src/test/resources/dcr/README.md）");
            return in.readAllBytes();
        }
    }

    private static long staticCalls(final MethodNode m, final String owner, final String name) {
        return Arrays.stream(m.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(owner) && mi.name.equals(name))
                .count();
    }

    private static MethodNode method(final ClassNode node, final String name, final String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }
}
