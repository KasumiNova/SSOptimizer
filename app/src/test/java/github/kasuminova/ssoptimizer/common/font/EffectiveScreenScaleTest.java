package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectiveScreenScaleTest {
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        EffectiveScreenScale.clearConfiguredOverrideCache();
    }

    @Test
    void parsesCommonScaleFormats() {
        assertEquals(1.5f, EffectiveScreenScale.parseScale("1.5"));
        assertEquals(1.5f, EffectiveScreenScale.parseScale("1.5x"));
        assertEquals(1.5f, EffectiveScreenScale.parseScale("150%"));
        assertEquals(0.0f, EffectiveScreenScale.parseScale("nope"));
    }

    @Test
    void prefersLargerOfGameAndDesktopScale() {
        assertEquals(1.5f, EffectiveScreenScale.resolve(1.0f, 1.5f));
        assertEquals(1.75f, EffectiveScreenScale.resolve(1.75f, 1.5f));
        assertEquals(1.0f, EffectiveScreenScale.resolve(Float.NaN, 0.0f));
    }

    @Test
    void prefersGameApiScaleOverSettingsFileFallbackWhenSettingsAreAvailable() {
        assertEquals(1.5f, EffectiveScreenScale.resolveCurrent(true, 1.5f, 1.0f, 0.0f, 2.0f));
        assertEquals(1.25f, EffectiveScreenScale.resolveCurrent(true, 1.0f, 1.25f, 0.0f, 2.0f));
    }

    @Test
    void fallsBackToGamePrefsBeforeGameSettingsAreAvailable() {
        // gamePrefs 可用时，取 gamePrefs 和 desktop 的较大值
        assertEquals(1.5f, EffectiveScreenScale.resolveCurrent(false, 1.0f, 1.0f, 1.5f, 2.0f));
        assertEquals(1.75f, EffectiveScreenScale.resolveCurrent(false, 1.0f, 1.75f, 1.5f, 0.0f));
    }

    @Test
    void fallsBackToConfiguredOverrideWhenGamePrefsUnavailable() {
        assertEquals(1.5f, EffectiveScreenScale.resolveCurrent(false, 1.0f, 1.0f, 0.0f, 1.5f));
        assertEquals(1.25f, EffectiveScreenScale.resolveCurrent(false, 1.0f, 1.25f, 0.0f, 0.0f));
    }

    @Test
    void readsScreenScaleOverrideFromSettingsJson(@TempDir final Path tempDir) throws IOException {
        final Path settingsFile = tempDir.resolve("settings.json");
        Files.writeString(settingsFile, "{\n  \"screenScaleOverride\":1.5, # comment\n}\n");

        assertEquals(1.5f, EffectiveScreenScale.readConfiguredOverrideScale(settingsFile));
    }

    @Test
    void cachedSettingsOverrideRefreshesAfterSettingsTimestampChanges(@TempDir final Path tempDir) throws IOException {
        final Path settingsFile = tempDir.resolve("settings.json");
        Files.writeString(settingsFile, "{\n  \"screenScaleOverride\":1.5\n}\n");

        assertEquals(1.5f, EffectiveScreenScale.readCachedConfiguredOverrideScale(settingsFile));

        Files.writeString(settingsFile, "{\n  \"screenScaleOverride\":2.0\n}\n");
        final long bumpedTimestamp = Files.getLastModifiedTime(settingsFile).toMillis() + 1000L;
        Files.setLastModifiedTime(settingsFile, FileTime.fromMillis(bumpedTimestamp));

        assertEquals(2.0f, EffectiveScreenScale.readCachedConfiguredOverrideScale(settingsFile));
    }
}