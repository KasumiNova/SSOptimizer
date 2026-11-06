package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcrBatchSaveIntegrationTest {

    private static final String PLUGIN = "data.scripts.combatanalytics.DetailedCombatResultsModPlugin";
    private static final String SERIALIZATION_MANAGER = "data.scripts.combatanalytics.SerializationManager";

    @Test
    void baselineSavesOncePerItem() throws Exception {
        TestProbe.reset();
        final TestModPlugin plugin =
                (TestModPlugin) Class.forName(PLUGIN).getDeclaredConstructor(int.class).newInstance(5);

        plugin.onGameLoad(true);

        assertEquals(5, plugin.cacheSize(), "夹具应处理 5 条战报");
        assertEquals(plugin.cacheSize(), TestProbe.saveCount,
                "基线：逐条 saveCombatResult → 存盘次数 == 战报条数");
    }

    @Test
    void transformedCoalescesToSingleSavePreservingFinalState() throws Exception {
        TestProbe.reset();
        final TestModPlugin plugin = loadTransformedPlugin(5);

        plugin.onGameLoad(true);

        assertEquals(1, TestProbe.saveCount, "转换后整个 trim 循环只存盘一次（flush）");
        assertNotNull(TestProbe.lastSavedXml, "flush 应真正序列化一次");
        assertEquals(5, plugin.cacheSize(), "末态缓存应含全部 5 条，与逐条路径一致");
        final String xml = TestProbe.lastSavedXml;
        int previous = -1;
        for (int i = 0; i < 5; i++) {
            final int at = xml.indexOf("id-" + i);
            assertTrue(at >= 0, "序列化内容应含 id-" + i);
            assertTrue(at > previous, "id 应按升序出现（已排序）：id-" + i);
            previous = at;
        }
    }

    @Test
    void transformedFlushIsNoOpWhenNoCollect() throws Exception {
        TestProbe.reset();
        final TestModPlugin plugin = loadTransformedPlugin(0);

        plugin.onGameLoad(true);

        assertEquals(0, TestProbe.saveCount, "无 collect 时 flush 应早返回、零存盘（不冗余写）");
        assertEquals(0, plugin.cacheSize(), "缓存应为空");
    }

    @Test
    void transformedFlushSwallowsSaveFailureLikeOriginal() throws Exception {
        TestProbe.reset();
        TestProbe.failSaves = true;
        final TestModPlugin plugin = loadTransformedPlugin(5);

        assertDoesNotThrow(() -> plugin.onGameLoad(true), "flush 须吞掉序列化/存盘异常，不抛出 onGameLoad");
        assertEquals(1, TestProbe.errorCount, "应经 Helpers.printErrorMessage 记录一次错误");
        assertEquals(0, TestProbe.saveCount, "saveValue 抛错前未记录存盘");
    }

    private static TestModPlugin loadTransformedPlugin(final int itemCount) throws Exception {
        final TransformingClassLoader loader = new TransformingClassLoader(
                DcrBatchSaveIntegrationTest.class.getClassLoader(),
                Map.of(
                        SERIALIZATION_MANAGER, new DcrBatchSaveSynthProcessor(),
                        PLUGIN, new DcrOnGameLoadProcessor()));
        final Class<?> pluginClass = loader.loadClass(PLUGIN);
        return (TestModPlugin) pluginClass.getDeclaredConstructor(int.class).newInstance(itemCount);
    }
}
