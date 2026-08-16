package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证原版加载噪音日志阈值的真实生效：configure() 对噪音 logger 的实际级别影响、
 * 对 SSOptimizer 自身/非噪音 logger 的隔离，以及阈值属性的解析。
 *
 * <p>测试运行期使用真实 log4j 1.x（testRuntime 的 log4j-1.2.17），
 * {@code Logger.setLevel} 行为与生产运行期 log4j-1.2-api 桥接一致。</p>
 */
class VanillaLogNoiseConfiguratorTest {

    /** 噪音名单抽样（覆盖主要类别：资源/规则/脚本/纹理/声音）。 */
    private static final String[] SAMPLE_NOISE_LOGGERS = {
            "loading.LoadingUtils",
            "loading.SpecStore",
            "loading.WeaponSpecLoader",
            "rules.Rules",
            "scripts.ScriptStore",
            "graphics.TextureLoader",
            "sound.Sound",
            "util.ShipColors",
    };

    @AfterEach
    void restoreLoggerLevels() {
        // 恢复全部噪音 logger 级别为继承（null），并还原系统属性，避免污染其他测试
        for (String name : SAMPLE_NOISE_LOGGERS) {
            Logger.getLogger(name).setLevel(null);
        }
        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        if (original == null) {
            System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        } else {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, original);
        }
    }

    @Test
    void defaultThresholdSuppressesInfoButKeepsWarnAndError() {
        // 基线：未配置前噪音 logger 继承 root（INFO enabled）
        assertTrue(Logger.getLogger("loading.LoadingUtils").isInfoEnabled());

        VanillaLogNoiseConfigurator.configure();

        for (String name : SAMPLE_NOISE_LOGGERS) {
            final Logger logger = Logger.getLogger(name);
            assertFalse(logger.isInfoEnabled(), name + " 的 INFO 应被压制");
            assertTrue(logger.isEnabledFor(Level.WARN), name + " 的 WARN 应保留");
            assertTrue(logger.isEnabledFor(Level.ERROR), name + " 的 ERROR 应保留");
        }
    }

    @Test
    void configureKeepsSsoptimizerOwnLoggersUntouched() {
        VanillaLogNoiseConfigurator.configure();

        // SSOptimizer 自身 logger 不在名单内，必须保持 INFO 可见
        assertTrue(Logger.getLogger("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin").isInfoEnabled());
        assertTrue(Logger.getLogger("github.kasuminova.ssoptimizer.SSOptimizerModPlugin").isInfoEnabled());
        assertTrue(Logger.getLogger(VanillaLogNoiseConfigurator.class).isInfoEnabled());
    }

    @Test
    void configureKeepsNonNoiseVanillaLoggersUntouched() {
        VanillaLogNoiseConfigurator.configure();

        // 非噪音原版 logger（启动器/显示层）不应被波及
        assertTrue(Logger.getLogger("starfarer.StarfarerLauncher").isInfoEnabled());
        assertTrue(Logger.getLogger("opengl.GLLauncher").isInfoEnabled());
        // 缺失纹理报错 logger（全 ERROR）不在名单内，必须保留
        assertTrue(Logger.getLogger("util.TextureData").isErrorEnabled());
    }

    @Test
    void infoThresholdLeavesNoiseLoggersUntouched() {
        System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "INFO");

        VanillaLogNoiseConfigurator.configure();

        // 用户显式要求保留完整加载日志：噪音 logger 维持继承（INFO enabled）
        assertTrue(Logger.getLogger("loading.LoadingUtils").isInfoEnabled());
        assertTrue(Logger.getLogger("scripts.ScriptStore").isInfoEnabled());
    }

    @Test
    void debugThresholdLeavesNoiseLoggersUntouched() {
        System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "DEBUG");

        VanillaLogNoiseConfigurator.configure();

        assertTrue(Logger.getLogger("rules.Rules").isInfoEnabled());
    }

    @Test
    void thresholdParsingFallsBackToWarn() {
        assertEquals(Level.WARN, VanillaLogNoiseConfigurator.vanillaThreshold());

        final String original = System.getProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
        try {
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "ERROR");
            assertEquals(Level.ERROR, VanillaLogNoiseConfigurator.vanillaThreshold());

            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "DEBUG");
            assertEquals(Level.DEBUG, VanillaLogNoiseConfigurator.vanillaThreshold());

            // TRACE 在 log4j 1.x 无对应级别，视为 DEBUG（与 LunaLib 阈值解析一致）
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "TRACE");
            assertEquals(Level.DEBUG, VanillaLogNoiseConfigurator.vanillaThreshold());

            // 空白/非法值回退 WARN
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "   ");
            assertEquals(Level.WARN, VanillaLogNoiseConfigurator.vanillaThreshold());
            System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, "BOGUS");
            assertEquals(Level.WARN, VanillaLogNoiseConfigurator.vanillaThreshold());
        } finally {
            if (original == null) {
                System.clearProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY);
            } else {
                System.setProperty(VanillaLogNoiseConfigurator.VANILLA_LEVEL_PROPERTY, original);
            }
        }
    }
}
