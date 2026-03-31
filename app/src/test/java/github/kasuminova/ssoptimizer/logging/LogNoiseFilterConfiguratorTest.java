package github.kasuminova.ssoptimizer.logging;

import org.apache.log4j.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogNoiseFilterConfiguratorTest {
    @Test
    void suppressesLunaLibDebugWhenThresholdIsWarn() {
        assertTrue(LogNoiseFilterConfigurator.shouldSuppress(
                "lunalib.backend.ui.settings.LunaSettingsLoader",
                Level.DEBUG,
                Level.WARN
        ));
        assertFalse(LogNoiseFilterConfigurator.shouldSuppress(
                "github.kasuminova.ssoptimizer.agent.SSOptimizerAgent",
                Level.DEBUG,
                Level.WARN
        ));
        assertFalse(LogNoiseFilterConfigurator.shouldSuppress(
                "lunalib.backend.ui.settings.LunaSettingsLoader",
                Level.ERROR,
                Level.WARN
        ));
    }

    @Test
    void treatsTraceAliasAsDebug() {
        final String original = System.getProperty(LogNoiseFilterConfigurator.LUNALIB_LEVEL_PROPERTY);
        try {
            System.setProperty(LogNoiseFilterConfigurator.LUNALIB_LEVEL_PROPERTY, "TRACE");
            assertSame(Level.DEBUG, LogNoiseFilterConfigurator.lunaLibThreshold());
        } finally {
            if (original == null) {
                System.clearProperty(LogNoiseFilterConfigurator.LUNALIB_LEVEL_PROPERTY);
            } else {
                System.setProperty(LogNoiseFilterConfigurator.LUNALIB_LEVEL_PROPERTY, original);
            }
        }
    }
}