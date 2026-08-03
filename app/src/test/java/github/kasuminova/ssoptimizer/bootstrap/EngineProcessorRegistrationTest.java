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

        // 11 个引擎级注册项（含 RESOURCE_LOADER 组合处理器）+ 3 个 DCR 处理器
        assertEquals(14, processors.size());
        assertTrue(processors.containsKey(GameClassNames.TEXTURE_LOADER));
        assertTrue(processors.containsKey(GameClassNames.COMBAT_STATE));
        assertTrue(processors.containsKey(GameClassNames.RESOURCE_LOADER));
        assertTrue(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(DcrOnGameLoadProcessor.TARGET_CLASS));
    }

    @Test
    void disableSwitchSkipsSingleProcessor() {
        String original = System.getProperty("ssoptimizer.disable.textureloader");
        try {
            System.setProperty("ssoptimizer.disable.textureloader", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(13, processors.size());
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

            assertEquals(11, processors.size());
            assertFalse(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        } finally {
            restoreProperty("ssoptimizer.disable.dcr", original);
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
