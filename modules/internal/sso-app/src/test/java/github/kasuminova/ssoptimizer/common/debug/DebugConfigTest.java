package github.kasuminova.ssoptimizer.common.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DebugConfig} 系统属性解析测试。
 */
class DebugConfigTest {
    @AfterEach
    void cleanProperties() {
        System.clearProperty(DebugConfig.ENABLED_PROPERTY);
        System.clearProperty(DebugConfig.PORT_PROPERTY);
        System.clearProperty(DebugConfig.OUTPUT_DIR_PROPERTY);
        System.clearProperty(DebugConfig.TOKEN_PROPERTY);
    }

    @Test
    void defaultsAreDisabledWithDefaultPort() {
        final DebugConfig config = DebugConfig.fromSystemProperties();
        assertFalse(config.enabled());
        assertEquals(DebugConfig.DEFAULT_PORT, config.port());
        assertEquals("", config.token());
        assertEquals(DebugConfig.TOKEN_FILE, config.tokenPath().getFileName().toString());
    }

    @Test
    void overridesFromSystemProperties() {
        System.setProperty(DebugConfig.ENABLED_PROPERTY, "true");
        System.setProperty(DebugConfig.PORT_PROPERTY, "9100");
        System.setProperty(DebugConfig.TOKEN_PROPERTY, "fixed-token");
        final DebugConfig config = DebugConfig.fromSystemProperties();
        assertTrue(config.enabled());
        assertEquals(9100, config.port());
        assertEquals("fixed-token", config.token());
    }

    @Test
    void explicitOutputDirWins() {
        System.setProperty(DebugConfig.OUTPUT_DIR_PROPERTY, "/tmp/sso-debug-test-out");
        final DebugConfig config = DebugConfig.fromSystemProperties();
        assertEquals("/tmp/sso-debug-test-out/" + DebugConfig.TOKEN_FILE, config.tokenPath().toString());
    }
}
