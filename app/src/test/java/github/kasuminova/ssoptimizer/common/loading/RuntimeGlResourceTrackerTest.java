package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeGlResourceTrackerTest {
    @AfterEach
    void tearDown() {
        RuntimeGlResourceTracker.resetForTest();
        System.clearProperty(RuntimeGlResourceTracker.SUMMARY_LOG_INTERVAL_MILLIS_PROPERTY);
    }

    @Test
    void summarySeparatesRuntimeFileBackedAndRenderbufferBytes() {
        RuntimeGlResourceTracker.trackTextureForTest(1, 8L << 20, false, "org.dark.shaders.util.ShaderLib");
        RuntimeGlResourceTracker.trackTextureForTest(2, 4L << 20, false, "org.dark.shaders.light.LightShader");
        RuntimeGlResourceTracker.trackTextureForTest(3, 16L << 20, true, "managed-file-backed");
        RuntimeGlResourceTracker.trackRenderbufferForTest(4, 2L << 20, false, "org.dark.shaders.util.ShaderLib");

        String summary = RuntimeGlResourceTracker.formatSummary();

        assertTrue(summary.contains("runtimeTextureMiB=12.0"));
        assertTrue(summary.contains("fileBackedTextureMiB=16.0"));
        assertTrue(summary.contains("renderbufferMiB=2.0"));
        assertTrue(summary.contains("liveMiB=30.0"));
        assertTrue(summary.contains("org.dark.shaders.util.ShaderLib=10.0MiB"));
        assertTrue(summary.contains("org.dark.shaders.light.LightShader=4.0MiB"));
    }

    @Test
    void deletingTrackedTextureRemovesItsContribution() {
        RuntimeGlResourceTracker.trackTextureForTest(7, 8L << 20, false, "org.dark.shaders.util.ShaderLib");
        RuntimeGlResourceTracker.removeTextureForTest(7);

        String summary = RuntimeGlResourceTracker.formatSummary();

        assertTrue(summary.contains("runtimeTextureMiB=0.0"));
        assertTrue(summary.contains("liveMiB=0.0"));
        assertTrue(summary.contains("topRuntimeOwners=(none)"));
    }

    @Test
    void summaryLogIntervalDefaultsToPositiveValue() {
        assertEquals(RuntimeGlResourceTracker.DEFAULT_SUMMARY_LOG_INTERVAL_MILLIS,
                RuntimeGlResourceTracker.summaryLogIntervalMillis());
    }
}
