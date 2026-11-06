package github.kasuminova.ssoptimizer.modopt.dcr;

import data.scripts.combatanalytics.DetailedCombatResultsModPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcrBatchSaveIntegrationTest {

    private static final String PLUGIN = "data.scripts.combatanalytics.DetailedCombatResultsModPlugin";
    private static final String SERIALIZATION_MANAGER = "data.scripts.combatanalytics.SerializationManager";
    private static final String PLUGIN_FACTORY = "github/kasuminova/ssoptimizer/modopt/dcr/DcrTestPluginFactory";

    @Test
    void baselineSavesOncePerItem() {
        TestProbe.reset();
        final TestModPlugin plugin = new DetailedCombatResultsModPlugin(5);

        plugin.onGameLoad(true);

        assertEquals(5, plugin.cacheSize(), "夹具应处理全部战报");
        assertEquals(plugin.cacheSize(), TestProbe.saveCount,
                "基线：每次 saveCombatResult 都应触发一次存盘");
    }

    @Test
    void transformedCoalescesToSingleSavePreservingFinalState() {
        TestProbe.reset();
        final TestModPlugin plugin = loadTransformedPlugin(5);

        plugin.onGameLoad(true);

        assertEquals(1, TestProbe.saveCount, "转换后整个 trim 循环只存盘一次（flush）");
        assertNotNull(TestProbe.lastSavedXml, "flush 应真正序列化一次");
        assertEquals(5, plugin.cacheSize(), "末态缓存应含全部战报，与逐条路径一致");
        final String xml = TestProbe.lastSavedXml;
        int previous = -1;
        for (int i = 0; i < plugin.cacheSize(); i++) {
            final int at = xml.indexOf("id-" + i);
            assertTrue(at >= 0, "序列化内容应含 id-" + i);
            assertTrue(at > previous, "id 应按升序出现（已排序）：id-" + i);
            previous = at;
        }
    }

    @Test
    void transformedFlushIsNoOpWhenNoCollect() {
        TestProbe.reset();
        final TestModPlugin plugin = loadTransformedPlugin(0);

        plugin.onGameLoad(true);

        assertEquals(0, TestProbe.saveCount, "无 collect 时 flush 应早返回、零存盘（不冗余写）");
        assertEquals(0, plugin.cacheSize(), "缓存应为空");
    }

    @Test
    void transformedFlushSwallowsSaveFailureLikeOriginal() {
        TestProbe.reset();
        TestProbe.failSaves = true;
        final TestModPlugin plugin = loadTransformedPlugin(5);

        assertDoesNotThrow(() -> plugin.onGameLoad(true), "flush 须吞掉序列化/存盘异常，不抛出 onGameLoad");
        assertEquals(1, TestProbe.errorCount, "应经 Helpers.printErrorMessage 记录一次错误");
        assertEquals(0, TestProbe.saveCount, "saveValue 抛错前未记录存盘");
    }

    private static TestModPlugin loadTransformedPlugin(final int itemCount) {
        final TransformingClassLoader pluginLoader = new TransformingClassLoader(
                DcrBatchSaveIntegrationTest.class.getClassLoader(),
                Map.of(
                        SERIALIZATION_MANAGER, new DcrBatchSaveSynthProcessor(),
                        PLUGIN, new DcrOnGameLoadProcessor()));
        final ByteArrayLoader factoryLoader = new ByteArrayLoader(pluginLoader);
        final String factoryName = PLUGIN_FACTORY.replace('/', '.');
        factoryLoader.define(factoryName, pluginFactoryBytes());
        final PluginFactory factory = github.kasuminova.ssoptimizer.testing.ClassInstances.create(
                PluginFactory.class, factoryLoader, factoryName);
        return factory.create(itemCount);
    }

    private static byte[] pluginFactoryBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                PLUGIN_FACTORY, null, "java/lang/Object", new String[]{Type.getInternalName(PluginFactory.class)});
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor create = writer.visitMethod(Opcodes.ACC_PUBLIC, "create",
                "(I)" + Type.getDescriptor(TestModPlugin.class), null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, PLUGIN.replace('.', '/'));
        create.visitInsn(Opcodes.DUP);
        create.visitVarInsn(Opcodes.ILOAD, 1);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, PLUGIN.replace('.', '/'), "<init>", "(I)V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public interface PluginFactory {
        TestModPlugin create(int itemCount);
    }

    private static final class ByteArrayLoader extends ClassLoader {
        ByteArrayLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
