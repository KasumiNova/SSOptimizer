package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OriginalGameFontOverridesTest {
    @Test
    void defaultFontDirResolvesToModFontsUnderWorkingDirectory() {
        final Path fontDir = OriginalGameFontOverrides.resolveDefaultFontDir("../mods", Path.of("C:/Games/Starsector/starsector-core"));

        assertEquals(Path.of("C:/Games/Starsector/mods/ssoptimizer/fonts"), fontDir);
    }

    @Test
    void blankModsPathFallsBackToRelativeModsDirectory() {
        final Path fontDir = OriginalGameFontOverrides.resolveDefaultFontDir("", Path.of("/games/starsector"));

        assertEquals(Path.of("/games/starsector/mods/ssoptimizer/fonts"), fontDir);
    }
}