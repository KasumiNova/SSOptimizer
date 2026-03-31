package github.kasuminova.ssoptimizer;

import com.fs.starfarer.api.BaseModPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SSOptimizerModPluginTest {
    @Test
    void modPluginExtendsBaseModPlugin() {
        SSOptimizerModPlugin plugin = new SSOptimizerModPlugin();
        assertInstanceOf(BaseModPlugin.class, plugin);
    }
}