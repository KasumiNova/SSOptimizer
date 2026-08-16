package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证原版加载噪音日志阈值配置器的行为契约：阈值属性解析、噪音名单配置
 * （守护名单不混入 ERROR 专用 logger 或 SSOptimizer 自身 logger）、以及
 * configure() 在各阈值分支下的执行契约。
 *
 * <p>环境说明：Gradle 测试 worker 的 classpath 中 {@code org.apache.log4j} 由
 * gradle-api 内嵌的 no-op 桥接提供（{@code setLevel} 无效、root 固定 WARN），
 * 单测无法观测 log4j 级别状态；生产运行时（NanoForge）由 SSOptimizer shade 的
 * log4j-1.2-api 桥接提供，{@code setLevel} 经 {@code CategoryUtil} 映射到 log4j2
 * Configurator 即时生效（已字节码确认）。级别生效的端到端验证见
 * {@code tools/verify_vanilla_log_noise_filter.sh}（独立 JVM + 真实 log4j-1.2.17）与
 * {@code tools/verify_vanilla_log_noise_runtime.sh}（log4j-1.2-api + log4j2 逐字节复现
 * 游戏运行时链路）。</p>
 */
class VanillaLogNoiseConfiguratorTest {

    // ---- 阈值属性解析 ----

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

    // ---- 噪音名单契约 ----

    @Test
    void noiseListCoversAllLoadingPhaseLoggers() {
        // 名单必须覆盖日志实证的高频噪音类别：资源加载/规则/脚本/纹理/声音/Mod 扫描
        // logger 名必须为运行时全名（FQCN）——游戏代码 Logger.getLogger(Class) 走 Class.getName()
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
        // 无重复项
        assertEquals(VanillaLogNoiseConfigurator.NOISE_LOGGER_NAMES.length,
                java.util.Arrays.stream(VanillaLogNoiseConfigurator.NOISE_LOGGER_NAMES).distinct().count(),
                "名单存在重复 logger");
    }

    @Test
    void noiseListNeverContainsErrorOnlyLoggers() {
        // util.TextureData 全 ERROR（缺失纹理报错），加入名单会吞掉用户要看的错误——绝不允许
        assertFalse(contains("util.TextureData"), "ERROR 专用 logger 不得入名单");
        // 名单内不得出现日志显示截断名（生产运行时 logger 是 FQCN，截断名 setLevel 无效）
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
        // util.ShipColors 是第三方 mod（顶层 util 包，游戏反编译源码无此类），非原版加载噪音
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

    // ---- configure() 执行契约 ----

    @Test
    void defaultConfigureRunsIdempotently() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
            // 连续两次调用：幂等、不抛异常（生产路径按默认 WARN 阈值执行 setLevel）
            VanillaLogNoiseConfigurator.configure();
            VanillaLogNoiseConfigurator.configure();
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void infoThresholdConfigureRuns() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            // INFO 阈值：setLevel(INFO) 分支（效果等同恢复 INFO 可见）
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "INFO");
            VanillaLogNoiseConfigurator.configure();
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void debugThresholdConfigureIsPassthrough() {
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            // DEBUG 阈值：passthrough 分支，不做任何级别修改
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

    private static void restoreProperty(String original) {
        if (original == null) {
            System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        } else {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, original);
        }
    }
}
