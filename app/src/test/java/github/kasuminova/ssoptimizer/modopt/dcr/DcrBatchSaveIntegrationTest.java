package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 端到端执行集成测试：实际加载并运行被 {@link DcrOnGameLoadProcessor} + {@link DcrBatchSaveSynthProcessor}
 * 转换后的夹具字节码，证明：
 * <ol>
 *   <li>基线（未转换）逐条 saveCombatResult → 每条一次存盘（O(N) 次存盘/压缩）；</li>
 *   <li>转换后 trim 循环合并为「收集 N 次 + flush 一次」→ 仅一次存盘，且末态序列化内容（全部 id、有序）一致；</li>
 *   <li>无 trim（循环跳过、collect 未调用）时 flush 走脏标志早返回 → 零存盘、不覆盖；</li>
 *   <li>序列化/存盘抛错时 flush 的 try/catch 捕获、记录、正常返回，不把异常抛出 onGameLoad。</li>
 * </ol>
 * JVM 在加载转换后的类时会校验栈帧，故本测试通过即证明注入产生的字节码合法、可链接。
 * 仅 {@code newInstance} 一处反射，属规范允许的动态加载/注入框架管线。
 */
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
        // 末态等价性：序列化内容应含全部 5 个 id，且按 combatId 升序（flush 序列化完整且已排序的列表）。
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
        // itemCount=0：循环跳过 → collect 从不调用 → dirty 保持 false → flush 早返回。
        final TestModPlugin plugin = loadTransformedPlugin(0);

        plugin.onGameLoad(true);

        assertEquals(0, TestProbe.saveCount, "无 collect 时 flush 应早返回、零存盘（不冗余写）");
        assertEquals(0, plugin.cacheSize(), "缓存应为空");
    }

    @Test
    void transformedFlushSwallowsSaveFailureLikeOriginal() throws Exception {
        TestProbe.reset();
        TestProbe.failSaves = true; // 让 flush 的 saveValue 抛异常
        final TestModPlugin plugin = loadTransformedPlugin(5);

        // 关键：异常必须被 flush 的 try/catch 捕获，不抛出 onGameLoad（镜像原 saveCombatResult 的逐次保护）。
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
