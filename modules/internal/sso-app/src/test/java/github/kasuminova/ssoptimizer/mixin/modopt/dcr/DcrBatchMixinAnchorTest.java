package github.kasuminova.ssoptimizer.mixin.modopt.dcr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DcrBatchMixinAnchorTest {
    private static final String SERIALIZATION_MANAGER = "data/scripts/combatanalytics/SerializationManager";
    private static final String PLUGIN = "data/scripts/combatanalytics/DetailedCombatResultsModPlugin";

    @Test
    void onGameLoadHasSingleListIteratorCall() throws IOException {
        final MethodNode onGameLoad = findMethod(PLUGIN, "onGameLoad", "(Z)V");
        assertEquals(1, countCalls(onGameLoad, "java/util/List", "iterator", "()Ljava/util/Iterator;"),
                "onGameLoad 内必须恰有一个 List.iterator() 调用点（裁剪后列表的遍历入口）");
    }

    @Test
    void saveCombatResultHasSingleToXmlAndSaveValueCall() throws IOException {
        final MethodNode save = findMethod(SERIALIZATION_MANAGER, "saveCombatResult",
                "(Ldata/scripts/combatanalytics/data/CombatResult;)V");
        assertEquals(1, countCalls(save, "com/thoughtworks/xstream/XStream", "toXML",
                        "(Ljava/lang/Object;)Ljava/lang/String;"),
                "saveCombatResult 内必须恰有一个 XStream.toXML 调用点");
        assertEquals(1, countCalls(save, SERIALIZATION_MANAGER, "saveValue",
                        "(Ljava/lang/String;Ljava/lang/String;)V"),
                "saveCombatResult 内必须恰有一个 saveValue 调用点");
    }

    private static int countCalls(final MethodNode method, final String owner, final String name, final String desc) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) {
                count++;
            }
        }
        return count;
    }

    private static MethodNode findMethod(final String internalName, final String name, final String desc) throws IOException {
        final String resource = "/dcr/" + internalName + ".class";
        final ClassNode node = new ClassNode();
        try (InputStream in = DcrBatchMixinAnchorTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "缺少 vendored 的真实 DCR 类字节: " + resource + "（见 src/test/resources/dcr/README.md）");
            new ClassReader(in).accept(node, 0);
        }
        for (final MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError(internalName + "." + name + desc + " 必须存在");
    }
}
