package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EngineProcessorRegistrationTest {

    private static Map<String, AsmClassProcessor> collectRegisteredProcessors() {
        Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        SSOptimizerCorePlugin.registerEngineProcessors(processors::put);
        return processors;
    }

    @Test
    void disableSwitchSkipsSingleProcessor() {
        String key = "ssoptimizer.disable.launcherdirectstart";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "true");

            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertFalse(processors.containsKey(GameClassNames.STARFARER_LAUNCHER));
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
