package github.kasuminova.ssoptimizer.modopt.dcr;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class DcrBatchCompatibility {
    private static final String SERIALIZATION_MANAGER = "data/scripts/combatanalytics/SerializationManager";
    private static final String SINGLE_SAVE_DESCRIPTOR = "(Ldata/scripts/combatanalytics/data/CombatResult;)V";

    private DcrBatchCompatibility() {
    }

    public static boolean needsBatchWindow(final ClassNode pluginClass) {
        final MethodNode onGameLoad = pluginClass.methods.stream()
                .filter(method -> method.name.equals("onGameLoad") && method.desc.equals("(Z)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "DCR " + pluginClass.name + " 缺少 onGameLoad(Z)V"));
        int singleSaves = 0;
        int batchSaves = 0;
        int iterators = 0;
        for (AbstractInsnNode instruction : onGameLoad.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(SERIALIZATION_MANAGER)) {
                if (call.name.equals("saveCombatResult") && call.desc.equals(SINGLE_SAVE_DESCRIPTOR)) {
                    singleSaves++;
                } else if (call.name.equals("saveCombatResults") && call.desc.equals("(Ljava/util/List;)V")) {
                    batchSaves++;
                }
            } else if (call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("iterator")
                    && call.desc.equals("()Ljava/util/Iterator;")) {
                iterators++;
            }
        }
        if (singleSaves == 1 && iterators == 1 && batchSaves == 0) {
            return true;
        }
        if (singleSaves == 0 && batchSaves == 1) {
            return false;
        }
        throw new IllegalStateException("DCR " + pluginClass.name
                + ".onGameLoad(Z)V 保存结构不受支持：saveCombatResult=" + singleSaves
                + ", List.iterator=" + iterators + ", saveCombatResults=" + batchSaves);
    }
}
