package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcrBatchCompatibilityTest {
    private static final String PLUGIN = "data/scripts/combatanalytics/DetailedCombatResultsModPlugin";
    private static final String SERIALIZATION_MANAGER = "data/scripts/combatanalytics/SerializationManager";
    private static final String SINGLE_SAVE_DESCRIPTOR = "(Ldata/scripts/combatanalytics/data/CombatResult;)V";

    @Test
    void legacyLoopNeedsBatchWindow() {
        assertTrue(DcrBatchCompatibility.needsBatchWindow(plugin(1, 1, 0)));
    }

    @Test
    void legacyClassBytesNeedBatchWindow() throws IOException {
        final ClassNode plugin = new ClassNode();
        try (InputStream input = getClass().getResourceAsStream("/dcr/" + PLUGIN + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(plugin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(DcrBatchCompatibility.needsBatchWindow(plugin));
    }

    @Test
    void nativeBatchSaveNeedsNoWindow() {
        assertFalse(DcrBatchCompatibility.needsBatchWindow(plugin(0, 0, 1)));
        assertFalse(DcrBatchCompatibility.needsBatchWindow(plugin(0, 2, 1)));
    }

    @Test
    void mixedSaveStrategiesAreRejected() {
        assertThrows(IllegalStateException.class,
                () -> DcrBatchCompatibility.needsBatchWindow(plugin(1, 1, 1)));
    }

    @Test
    void incompleteOrRepeatedSaveStructuresAreRejected() {
        for (int[] counts : new int[][]{{0, 0, 0}, {1, 0, 0}, {2, 1, 0}, {1, 2, 0}, {0, 0, 2}}) {
            assertThrows(IllegalStateException.class,
                    () -> DcrBatchCompatibility.needsBatchWindow(plugin(counts[0], counts[1], counts[2])));
        }
    }

    @Test
    void missingMethodAndWrongOverloadAreRejected() {
        final ClassNode plugin = new ClassNode();
        plugin.name = PLUGIN;
        assertThrows(IllegalStateException.class, () -> DcrBatchCompatibility.needsBatchWindow(plugin));
        plugin.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "onGameLoad", "()V", null, null));
        assertThrows(IllegalStateException.class, () -> DcrBatchCompatibility.needsBatchWindow(plugin));
    }

    @Test
    void saveCallsInOtherMethodsDoNotAffectClassification() {
        final ClassNode plugin = plugin(1, 1, 0);
        final MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC, "onApplicationLoad", "()V", null, null);
        other.instructions.add(batchSave());
        plugin.methods.add(other);
        assertTrue(DcrBatchCompatibility.needsBatchWindow(plugin));
    }

    @Test
    void saveSignatureAndInvocationKindMustMatch() {
        final MethodInsnNode[] unrelated = {
                new MethodInsnNode(Opcodes.INVOKESTATIC, "other/SerializationManager", "saveCombatResult",
                        SINGLE_SAVE_DESCRIPTOR, false),
                new MethodInsnNode(Opcodes.INVOKESTATIC, SERIALIZATION_MANAGER, "saveCombatResult",
                        "(Ljava/lang/Object;)V", false),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERIALIZATION_MANAGER, "saveCombatResult",
                        SINGLE_SAVE_DESCRIPTOR, false)
        };
        for (MethodInsnNode call : unrelated) {
            final ClassNode plugin = plugin(0, 1, 0);
            plugin.methods.getFirst().instructions.add(call);
            assertThrows(IllegalStateException.class, () -> DcrBatchCompatibility.needsBatchWindow(plugin));
        }
    }

    private static ClassNode plugin(final int singleSaves, final int iterators, final int batchSaves) {
        final ClassNode plugin = new ClassNode();
        plugin.name = PLUGIN;
        final MethodNode onGameLoad = new MethodNode(Opcodes.ACC_PUBLIC, "onGameLoad", "(Z)V", null, null);
        plugin.methods.add(onGameLoad);
        for (int i = 0; i < singleSaves; i++) {
            onGameLoad.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SERIALIZATION_MANAGER,
                    "saveCombatResult", SINGLE_SAVE_DESCRIPTOR, false));
        }
        for (int i = 0; i < iterators; i++) {
            onGameLoad.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List",
                    "iterator", "()Ljava/util/Iterator;", true));
        }
        for (int i = 0; i < batchSaves; i++) {
            onGameLoad.instructions.add(batchSave());
        }
        return plugin;
    }

    private static MethodInsnNode batchSave() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, SERIALIZATION_MANAGER,
                "saveCombatResults", "(Ljava/util/List;)V", false);
    }
}
