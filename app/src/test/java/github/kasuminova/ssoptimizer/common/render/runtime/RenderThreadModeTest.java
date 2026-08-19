package github.kasuminova.ssoptimizer.common.render.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RenderThreadMode#isEnabled()} 开关语义验证：默认启用（RT 流水线稳定版起
 * 设为默认路径），仅显式 {@code =false} 回退。
 */
class RenderThreadModeTest {

    private String previousFlag;

    @BeforeEach
    void saveFlag() {
        previousFlag = System.getProperty(RenderThreadMode.ENABLE_PROPERTY);
    }

    @AfterEach
    void restoreFlag() {
        if (previousFlag == null) {
            System.clearProperty(RenderThreadMode.ENABLE_PROPERTY);
        } else {
            System.setProperty(RenderThreadMode.ENABLE_PROPERTY, previousFlag);
        }
    }

    @Test
    void enabledByDefaultWhenPropertyAbsent() {
        System.clearProperty(RenderThreadMode.ENABLE_PROPERTY);
        assertTrue(RenderThreadMode.isEnabled());
    }

    @Test
    void enabledWhenPropertyTrue() {
        System.setProperty(RenderThreadMode.ENABLE_PROPERTY, "true");
        assertTrue(RenderThreadMode.isEnabled());
    }

    @Test
    void disabledOnlyWhenPropertyExplicitlyFalse() {
        System.setProperty(RenderThreadMode.ENABLE_PROPERTY, "false");
        assertFalse(RenderThreadMode.isEnabled());
        System.setProperty(RenderThreadMode.ENABLE_PROPERTY, "FALSE");
        assertFalse(RenderThreadMode.isEnabled());
    }
}
