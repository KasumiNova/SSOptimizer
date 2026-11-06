package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
