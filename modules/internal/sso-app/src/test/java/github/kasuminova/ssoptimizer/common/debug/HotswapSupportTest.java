package github.kasuminova.ssoptimizer.common.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HotswapSupportTest {
    private static final String FQN = "github.kasuminova.ssoptimizer.common.debug.HotswapFixture";

    @TempDir
    Path outputDir;

    @Test
    void redefineReplacesMethodBody() {
        assertTrue(Boolean.getBoolean("jdk.attach.allowAttachSelf"),
                "test JVM must run with -Djdk.attach.allowAttachSelf=true");
        assertEquals("original", new HotswapFixture().marker());
        final Map<String, byte[]> classes = new ScriptCompiler().compile(FQN, """
                package github.kasuminova.ssoptimizer.common.debug;
                public class HotswapFixture {
                    public String marker() {
                        return "hotswapped";
                    }
                }
                """);
        HotswapSupport.redefine(FQN, classes.get(FQN), outputDir);
        assertEquals("hotswapped", new HotswapFixture().marker());
    }

    @Test
    void redefineRejectsUnloadedClass() {
        final Map<String, byte[]> classes = new ScriptCompiler().compile(
                "NeverLoaded", "public class NeverLoaded { public int x() { return 1; } }");
        assertThrows(IllegalArgumentException.class,
                () -> HotswapSupport.redefine("NeverLoaded", classes.get("NeverLoaded"), outputDir));
    }

    @Test
    void ensureAttachedIsIdempotent() {
        assertTrue(Boolean.getBoolean("jdk.attach.allowAttachSelf"),
                "test JVM must run with -Djdk.attach.allowAttachSelf=true");
        assertSame(HotswapSupport.ensureAttached(outputDir), HotswapSupport.ensureAttached(outputDir));
    }
}
