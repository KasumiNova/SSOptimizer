package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VanillaLogNoiseConfiguratorTest {

    @Test
    void thresholdParsingDefaultsToWarn() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
            assertEquals(Level.WARN, VanillaLogNoiseConfigurator.vanillaThreshold());
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void thresholdParsingHonorsConfiguredLevels() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "ERROR");
            assertEquals(Level.ERROR, VanillaLogNoiseConfigurator.vanillaThreshold());

            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "INFO");
            assertEquals(Level.INFO, VanillaLogNoiseConfigurator.vanillaThreshold());
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void thresholdParsingTreatsTraceAsDebug() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "TRACE");
            assertEquals(Level.DEBUG, VanillaLogNoiseConfigurator.vanillaThreshold());

            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "DEBUG");
            assertEquals(Level.DEBUG, VanillaLogNoiseConfigurator.vanillaThreshold());
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void thresholdParsingFallsBackToWarnOnBlankOrInvalid() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "   ");
            assertEquals(Level.WARN, VanillaLogNoiseConfigurator.vanillaThreshold());

            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "BOGUS");
            assertEquals(Level.WARN, VanillaLogNoiseConfigurator.vanillaThreshold());
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void noiseListCoversAllLoadingPhaseLoggers() {
        assertTrue(contains("com.fs.starfarer.loading.LoadingUtils"), "LoadingUtils 缺失");
        assertTrue(contains("com.fs.starfarer.loading.SpecStore"), "SpecStore 缺失");
        assertTrue(contains("com.fs.starfarer.loading.WeaponSpreadsheetLoader"), "WeaponSpreadsheetLoader 缺失");
        assertTrue(contains("com.fs.starfarer.loading.WeaponSpecLoader"), "WeaponSpecLoader 缺失");
        assertTrue(contains("com.fs.starfarer.loading.ShipHullSpreadsheetLoader"), "ShipHullSpreadsheetLoader 缺失");
        assertTrue(contains("com.fs.starfarer.loading.ShipHullSpecLoader"), "ShipHullSpecLoader 缺失");
        assertTrue(contains("com.fs.starfarer.loading.FighterWingSpreadsheetLoader"), "FighterWingSpreadsheetLoader 缺失");
        assertTrue(contains("com.fs.starfarer.loading.HullVariantSpecStore"), "HullVariantSpecStore 缺失");
        assertTrue(contains("com.fs.starfarer.loading.ShipNameStore"), "ShipNameStore 缺失");
        assertTrue(contains("com.fs.starfarer.loading.scripts.ScriptStore"), "ScriptStore 缺失");
        assertTrue(contains("com.fs.starfarer.loading.scripts.ScriptClassLoader"), "ScriptClassLoader 缺失");
        assertTrue(contains("com.fs.starfarer.campaign.rules.Rules"), "Rules 缺失");
        assertTrue(contains("com.fs.graphics.TextureLoader"), "TextureLoader 缺失");
        assertTrue(contains("sound.Sound"), "Sound 缺失");
        assertTrue(contains("sound.Music"), "Music 缺失");
        assertTrue(contains("com.fs.starfarer.launcher.ModManager"), "ModManager 缺失");
        assertTrue(contains("com.fs.starfarer.api.impl.codex.CodexTextEntryLoader"), "CodexTextEntryLoader 缺失");
        assertEquals(VanillaLogNoiseConfigurator.NOISE_LOGGER_NAMES.length,
                java.util.Arrays.stream(VanillaLogNoiseConfigurator.NOISE_LOGGER_NAMES).distinct().count(),
                "名单存在重复 logger");
    }

    @Test
    void noiseListNeverContainsErrorOnlyLoggers() {
        assertFalse(contains("util.TextureData"), "ERROR 专用 logger 不得入名单");
        for (String name : VanillaLogNoiseConfigurator.NOISE_LOGGER_NAMES) {
            assertTrue(name.contains("."), "logger 名至少两段: " + name);
        }
        assertFalse(contains("loading.LoadingUtils"), "不得使用日志截断名 loading.LoadingUtils");
        assertFalse(contains("rules.Rules"), "不得使用日志截断名 rules.Rules");
        assertFalse(contains("graphics.TextureLoader"), "不得使用日志截断名 graphics.TextureLoader");
        assertFalse(contains("scripts.ScriptStore"), "不得使用日志截断名 scripts.ScriptStore");
    }

    @Test
    void noiseListNeverContainsThirdPartyModLoggers() {
        assertFalse(contains("util.ShipColors"), "第三方 mod logger 不得入名单");
        assertFalse(contains("camp.Mimikko_priority_deployment_plugin"));
        assertFalse(contains("hullmods.No101_CoincidenceRangefinder"));
    }

    @Test
    void noiseListNeverContainsSsoptimizerOwnLoggers() {
        for (String name : VanillaLogNoiseConfigurator.NOISE_LOGGER_NAMES) {
            assertFalse(name.startsWith("github.kasuminova.ssoptimizer"),
                    "SSOptimizer 自身 logger 不得入名单: " + name);
        }
        assertFalse(contains("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin"));
        assertFalse(contains("github.kasuminova.ssoptimizer.common.loading.LazyTextureManager"));
    }

    @Test
    void defaultConfigureRunsIdempotently() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
            VanillaLogNoiseConfigurator.configure();
            VanillaLogNoiseConfigurator.configure();
        } finally {
            restoreProperty(original);
            removeAggregateFilterIfPresent();
        }
    }

    @Test
    void defaultConfigureInstallsAggregateFilterExactlyOnce() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
            VanillaLogNoiseConfigurator.configure();
            VanillaLogNoiseConfigurator.configure();

            final org.apache.logging.log4j.core.Filter installed =
                    rootLoggerConfig().getFilter();
            assertTrue(installed instanceof LoadingNoiseLog4j2Filter,
                    "默认 WARN 阈值必须把聚合过滤器挂到 log4j2 root LoggerConfig");
        } finally {
            restoreProperty(original);
            removeAggregateFilterIfPresent();
        }
    }

    @Test
    void infoThresholdConfigureSkipsAggregateFilter() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            removeAggregateFilterIfPresent();
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "INFO");
            VanillaLogNoiseConfigurator.configure();

            final org.apache.logging.log4j.core.Filter installed =
                    rootLoggerConfig().getFilter();
            assertFalse(installed instanceof LoadingNoiseLog4j2Filter,
                    "INFO 阈值不得安装聚合过滤器");
        } finally {
            restoreProperty(original);
            removeAggregateFilterIfPresent();
        }
    }

    @Test
    void infoThresholdConfigureRuns() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "INFO");
            VanillaLogNoiseConfigurator.configure();
        } finally {
            restoreProperty(original);
            removeAggregateFilterIfPresent();
        }
    }

    @Test
    void debugThresholdConfigureIsPassthrough() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "DEBUG");
            VanillaLogNoiseConfigurator.configure();
        } finally {
            restoreProperty(original);
        }
    }

    private static boolean contains(String loggerName) {
        for (String name : VanillaLogNoiseConfigurator.NOISE_LOGGER_NAMES) {
            if (name.equals(loggerName)) {
                return true;
            }
        }
        return false;
    }

    private static org.apache.logging.log4j.core.config.LoggerConfig rootLoggerConfig() {
        final org.apache.logging.log4j.core.LoggerContext context =
                (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        return context.getConfiguration().getRootLogger();
    }

    private static void removeAggregateFilterIfPresent() {
        final org.apache.logging.log4j.core.config.LoggerConfig root = rootLoggerConfig();
        final org.apache.logging.log4j.core.Filter installed = root.getFilter();
        if (installed instanceof LoadingNoiseLog4j2Filter) {
            root.removeFilter(installed);
        }
    }

    private static void restoreProperty(String original) {
        if (original == null) {
            System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        } else {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, original);
        }
    }
}
