package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrBatchSaveSynthProcessor;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrOnGameLoadProcessor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EngineProcessorRegistrationTest {

    private static Map<String, AsmClassProcessor> collectRegisteredProcessors() {
        Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        SSOptimizerCorePlugin.registerAllProcessors(processors::put);
        return processors;
    }

    @Test
    void disableSwitchSkipsSingleProcessor() {
        String key = "ssoptimizer.disable.textureloader";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "true");

            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertFalse(processors.containsKey(GameClassNames.TEXTURE_LOADER));
        } finally {
            restoreProperty(key, original);
        }
    }

    @Test
    void disableDcrSwitchSkipsAllDcrProcessors() {
        String key = "ssoptimizer.disable.dcr";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "true");

            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertFalse(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
            assertFalse(processors.containsKey(DcrOnGameLoadProcessor.TARGET_CLASS));
        } finally {
            restoreProperty(key, original);
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
