package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DcrModOptimizer} 的注册行为：featureKey 与 processors() 的目标类覆盖。
 * <p>
 * coremod 化后 DCR 源码已收编进主模块、由 {@code SSOptimizerCorePlugin} 直接注册，
 * 原 ServiceLoader SPI 发现机制（及对应测试）已随 javaagent 通道一并移除。
 * 压缩内核处理器已迁移为 Mixin，不再出现在 processors() 中。
 */
class DcrModOptimizerTest {

    private static final String SM = "data/scripts/combatanalytics/SerializationManager";
    private static final String PLUGIN = "data/scripts/combatanalytics/DetailedCombatResultsModPlugin";

    @Test
    void featureKeyIsDcr() {
        assertEquals("dcr", new DcrModOptimizer().featureKey());
    }

    @Test
    void processorsCoverBothRemainingTargets() {
        final Map<String, ?> processors = new DcrModOptimizer().processors();
        assertTrue(processors.containsKey(SM), "应含 SerializationManager 处理器");
        assertTrue(processors.containsKey(PLUGIN), "应含 plugin 处理器");
        assertEquals(2, processors.size(), "压缩内核已迁移为 Mixin，不应再经本集合注册");
    }
}
