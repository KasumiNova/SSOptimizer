package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrBatchSaveSynthProcessor;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrOnGameLoadProcessor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineProcessorRegistrationTest {

    private static Map<String, AsmClassProcessor> collectRegisteredProcessors() {
        Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        SSOptimizerCorePlugin.registerAllProcessors(processors::put);
        return processors;
    }

    @Test
    void registersEngineAndExternalModProcessors() {
        Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

        // 18 个引擎级注册项（含 RESOURCE_LOADER 组合处理器与 7 个并行 AI 处理器）+ 2 个 DCR 处理器
        assertEquals(20, processors.size());
        assertTrue(processors.containsKey(GameClassNames.TEXTURE_LOADER));
        assertTrue(processors.containsKey(GameClassNames.COMBAT_STATE));
        assertTrue(processors.containsKey(GameClassNames.RESOURCE_LOADER));
        assertTrue(processors.containsKey(GameClassNames.COMBAT_ENGINE));
        assertTrue(processors.containsKey(GameClassNames.AI_UTILS));
        assertTrue(processors.containsKey(GameClassNames.PROFILER));
        assertTrue(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(DcrOnGameLoadProcessor.TARGET_CLASS));
    }

    @Test
    void disableSwitchSkipsSingleProcessor() {
        String original = System.getProperty("ssoptimizer.disable.textureloader");
        try {
            System.setProperty("ssoptimizer.disable.textureloader", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(19, processors.size());
            assertFalse(processors.containsKey(GameClassNames.TEXTURE_LOADER));
        } finally {
            restoreProperty("ssoptimizer.disable.textureloader", original);
        }
    }

    @Test
    void disableDcrSwitchSkipsAllDcrProcessors() {
        String original = System.getProperty("ssoptimizer.disable.dcr");
        try {
            System.setProperty("ssoptimizer.disable.dcr", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(18, processors.size());
            assertFalse(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        } finally {
            restoreProperty("ssoptimizer.disable.dcr", original);
        }
    }

    @Test
    void disableAiParallelSwitchSkipsAllSevenProcessors() {
        String original = System.getProperty("ssoptimizer.disable.aiparallel");
        try {
            System.setProperty("ssoptimizer.disable.aiparallel", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(13, processors.size());
            assertFalse(processors.containsKey(GameClassNames.COMBAT_ENGINE));
            assertFalse(processors.containsKey(GameClassNames.AI_UTILS));
            assertFalse(processors.containsKey(GameClassNames.ATTACK_AI_MODULE));
            assertFalse(processors.containsKey(GameClassNames.PROFILER));
            assertFalse(processors.containsKey(GameClassNames.SHIPWIDE_AI_FLAGS));
            assertFalse(processors.containsKey(GameClassNames.TIMEOUT_TRACKER_MAP));
            assertFalse(processors.containsKey(GameClassNames.FIGHTER_AI));
        } finally {
            restoreProperty("ssoptimizer.disable.aiparallel", original);
        }
    }

    private static void restoreProperty(String key, String original) {
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }
}
