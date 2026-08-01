package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DcrModOptimizer} 的注册行为：featureKey、processors() 的目标类覆盖，以及 {@code disable.dcrzstd}
 * 子开关裁剪 CompressionUtil 处理器。
 * <p>
 * coremod 化后 DCR 源码已收编进主模块、由 {@code SSOptimizerCorePlugin} 直接注册，
 * 原 ServiceLoader SPI 发现机制（及对应测试）已随 javaagent 通道一并移除。
 */
class DcrModOptimizerTest {

    private static final String SM = "data/scripts/combatanalytics/SerializationManager";
    private static final String PLUGIN = "data/scripts/combatanalytics/DetailedCombatResultsModPlugin";
    private static final String COMPRESSION_UTIL = "data/scripts/combatanalytics/util/CompressionUtil";

    @Test
    void featureKeyIsDcr() {
        assertEquals("dcr", new DcrModOptimizer().featureKey());
    }

    @Test
    void processorsCoverAllThreeTargetsByDefault() {
        final Map<String, ?> processors = new DcrModOptimizer().processors();
        assertTrue(processors.containsKey(SM), "应含 SerializationManager 处理器");
        assertTrue(processors.containsKey(PLUGIN), "应含 plugin 处理器");
        assertTrue(processors.containsKey(COMPRESSION_UTIL), "默认应含 CompressionUtil(Zstd) 处理器");
    }

    @Test
    void zstdSubToggleExcludesCompressionProcessor() {
        final String prop = DcrModOptimizer.DISABLE_ZSTD_PROPERTY;
        final String previous = System.getProperty(prop);
        try {
            System.setProperty(prop, "true");
            final Map<String, ?> processors = new DcrModOptimizer().processors();
            assertTrue(processors.containsKey(SM), "L1 不受 zstd 子开关影响");
            assertTrue(processors.containsKey(PLUGIN), "L1 不受 zstd 子开关影响");
            assertFalse(processors.containsKey(COMPRESSION_UTIL),
                    "disable.dcrzstd=true 时应排除 CompressionUtil 处理器");
        } finally {
            if (previous == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, previous);
            }
        }
    }
}
