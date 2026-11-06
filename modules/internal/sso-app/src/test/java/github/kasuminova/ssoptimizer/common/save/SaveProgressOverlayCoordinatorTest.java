package github.kasuminova.ssoptimizer.common.save;

import com.fs.starfarer.api.Global;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveProgressOverlayCoordinatorTest {
    @AfterEach
    void resetCoordinator() {
        System.clearProperty(SaveProgressOverlayCoordinator.DISABLE_SAVE_OVERLAY_PROPERTY);
        System.clearProperty(SaveProgressOverlayCoordinator.SAVE_OVERLAY_FPS_OVERRIDE_PROPERTY);
        Global.setSettings(null);
        SaveProgressOverlayCoordinator.resetForTests();
    }

    @Test
    void byteProgressInterpolatesAcrossConfiguredPhaseWindow() {
        SaveProgressOverlayCoordinator.beginStreamPhase(200L, 0.25f, 0.75f);
        SaveProgressOverlayCoordinator.onBytesWritten(100L);

        SaveProgressOverlayCoordinator.SaveProgressSnapshot snapshot = SaveProgressOverlayCoordinator.snapshot();

        assertTrue(snapshot.visible(), "active save phase should expose a visible snapshot");
        assertEquals(0.50f, snapshot.progress(), 0.0001f,
                "half of the written bytes should map to the midpoint of the configured phase window");
    }

    @Test
    void reportedTextAndAutosaveLabelAreCaptured() {
        SaveProgressOverlayCoordinator.attachSaveLabel("autosave_03");
        SaveProgressOverlayCoordinator.reportProgress("保存中...", 32.5f);

        SaveProgressOverlayCoordinator.SaveProgressSnapshot snapshot = SaveProgressOverlayCoordinator.snapshot();

        assertTrue(snapshot.visible(), "reported progress should activate the overlay snapshot");
        assertTrue(snapshot.autoSave(), "autosave label should be recognized from the original slot text");
        assertEquals("autosave_03", snapshot.saveLabel());
        assertEquals("保存中...", snapshot.statusText());
        assertEquals(0.325f, snapshot.progress(), 0.0001f);
    }

    @Test
    void completeMarksSnapshotForFinalFrame() {
        SaveProgressOverlayCoordinator.beginStreamPhase(128L, 0.0f, 1.0f);
        SaveProgressOverlayCoordinator.onBytesWritten(64L);
        SaveProgressOverlayCoordinator.complete();

        SaveProgressOverlayCoordinator.SaveProgressSnapshot snapshot = SaveProgressOverlayCoordinator.snapshot();

        assertTrue(snapshot.visible(), "completed save should remain visible until the final render pump consumes it");
        assertTrue(snapshot.completed(), "complete should mark the snapshot for a final 100% frame");
        assertEquals(1.0f, snapshot.progress(), 0.0001f);
    }

    @Test
    void disabledOverlaySuppressesStatePublication() {
        System.setProperty(SaveProgressOverlayCoordinator.DISABLE_SAVE_OVERLAY_PROPERTY, "true");
        SaveProgressOverlayCoordinator.beginStreamPhase(64L, 0.0f, 1.0f);
        SaveProgressOverlayCoordinator.reportProgress("保存中...", 50.0f);

        SaveProgressOverlayCoordinator.SaveProgressSnapshot snapshot = SaveProgressOverlayCoordinator.snapshot();

        assertFalse(snapshot.visible(), "disabled overlay property should suppress all published state");
    }

    @Test
    void readsDisplayRefreshConfigFromSettingsApi() throws Exception {
        Global.setSettings(new TestSettings(new JSONObject()
                .put("vsync", false)
                .put("fps", 144)
                .put("refreshRateOverride", 0)));

        final SaveProgressOverlayCoordinator.DisplayRefreshConfig config =
                SaveProgressOverlayCoordinator.readDisplayRefreshConfig();

        assertFalse(config.vsyncEnabled());
        assertEquals(144, config.framesPerSecond());
        assertEquals(0, config.refreshRateOverride());
    }

    @Test
    void absentSettingsApiFallsBackToDefaultRenderConfig() {
        Global.setSettings(null);

        final SaveProgressOverlayCoordinator.DisplayRefreshConfig config =
                SaveProgressOverlayCoordinator.readDisplayRefreshConfig();

        assertFalse(config.vsyncEnabled());
        assertEquals(0, config.framesPerSecond());
        assertEquals(0, config.refreshRateOverride());
    }

    @Test
    void readsRefreshOverrideFromSettingsApiJson() throws Exception {
        Global.setSettings(new TestSettings(new JSONObject()
                .put("vsync", true)
                .put("fps", 160)
                .put("refreshRateOverride", 75)));

        final SaveProgressOverlayCoordinator.DisplayRefreshConfig config =
                SaveProgressOverlayCoordinator.readDisplayRefreshConfig();

        assertTrue(config.vsyncEnabled());
        assertEquals(160, config.framesPerSecond());
        assertEquals(75, config.refreshRateOverride());
    }

    @Test
    void renderIntervalUsesRefreshOverrideWhenVsyncIsEnabled() {
        final SaveProgressOverlayCoordinator.DisplayRefreshConfig config =
                new SaveProgressOverlayCoordinator.DisplayRefreshConfig(true, 160, 75);

        assertEquals(1_000_000_000L / 75,
                SaveProgressOverlayCoordinator.resolveRenderIntervalNanos(config));
    }

    @Test
    void renderIntervalFallsBackToConfiguredFpsWhenVsyncIsDisabled() {
        final SaveProgressOverlayCoordinator.DisplayRefreshConfig config =
                new SaveProgressOverlayCoordinator.DisplayRefreshConfig(false, 160, 0);

        assertEquals(1_000_000_000L / 160,
                SaveProgressOverlayCoordinator.resolveRenderIntervalNanos(config));
    }
}