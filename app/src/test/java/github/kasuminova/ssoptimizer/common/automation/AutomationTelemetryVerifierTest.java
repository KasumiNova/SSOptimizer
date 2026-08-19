package github.kasuminova.ssoptimizer.common.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SSOptimizer 自动化配置读取与 telemetry 验收合同。
 */
class AutomationTelemetryVerifierTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsCompletedArcFlareAod7TelemetryWithScreenshotAttempt() throws Exception {
        final Path screenshotAttempt = tempDir.resolve("astd-ingame-automation-screenshot-attempt.txt");
        Files.writeString(screenshotAttempt, "stage=init\nresult=attempt-recorded\n");
        final Path telemetry = tempDir.resolve(AutomationConfig.ASTD_TELEMETRY_FILE);
        Files.writeString(telemetry, completedTelemetry(screenshotAttempt, null));

        final AutomationTelemetryVerifier.VerificationResult result = AutomationTelemetryVerifier.verify(telemetry, false);

        assertTrue(result.passed(), String.join("\n", result.errors()));
        assertEquals(telemetry, result.telemetryPath());
    }

    @Test
    void failsWhenVfxWasNotObserved() throws Exception {
        final Path screenshotAttempt = tempDir.resolve("astd-ingame-automation-screenshot-attempt.txt");
        Files.writeString(screenshotAttempt, "stage=init\nresult=attempt-recorded\n");
        final Path telemetry = tempDir.resolve(AutomationConfig.ASTD_TELEMETRY_FILE);
        Files.writeString(telemetry, completedTelemetry(screenshotAttempt, null).replace("\"vfxObserved\": true", "\"vfxObserved\": false"));

        final AutomationTelemetryVerifier.VerificationResult result = AutomationTelemetryVerifier.verify(telemetry, false);

        assertFalse(result.passed());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("vfxObserved")));
    }

    @Test
    void requiresScreenshotFileWhenConfigured() throws Exception {
        final Path screenshotAttempt = tempDir.resolve("astd-ingame-automation-screenshot-attempt.txt");
        Files.writeString(screenshotAttempt, "stage=init\nresult=attempt-recorded\n");
        final Path telemetry = tempDir.resolve(AutomationConfig.ASTD_TELEMETRY_FILE);
        Files.writeString(telemetry, completedTelemetry(screenshotAttempt, null));

        final AutomationTelemetryVerifier.VerificationResult result = AutomationTelemetryVerifier.verify(telemetry, true);

        assertFalse(result.passed());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("screenshotPath")));
    }

    private static String completedTelemetry(final Path screenshotAttemptPath, final Path screenshotPath) {
        return """
                {
                  "source": "ASTD",
                  "simulationMock": false,
                  "scenario": "arc_flare_aod7_basic",
                  "state": "Completed",
                  "combatSceneObserved": true,
                  "shipObserved": true,
                  "shipId": "astd_arc_flare",
                  "variantId": "astd_arc_flare_Standard",
                  "weaponObserved": true,
                  "weaponId": "astd_aod7",
                  "projectileSpecId": "astd_aod7_shot",
                  "projectileObserved": true,
                  "vfxPresetId": "aod7_shot",
                  "vfxObserved": true,
                  "runtimeTrackedCount": 1,
                  "fireCommandIssued": true,
                  "fireMechanism": "spawnProjectileFallback",
                  "screenshotPath": %s,
                  "screenshotAttemptPath": "%s",
                  "elapsedSeconds": 1.750,
                  "failureReason": null
                }
                """.formatted(
                screenshotPath == null ? "null" : "\"" + screenshotPath + "\"",
                screenshotAttemptPath.toString().replace("\\", "\\\\")
        );
    }
}
