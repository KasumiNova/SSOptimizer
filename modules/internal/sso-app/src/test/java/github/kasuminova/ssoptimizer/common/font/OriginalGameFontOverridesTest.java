package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OriginalGameFontOverridesTest {
    @Test
    void enablesFontOverridesByDefault() {
        final String previous = System.getProperty(OriginalGameFontOverrides.ENABLE_PROPERTY);
        System.clearProperty(OriginalGameFontOverrides.ENABLE_PROPERTY);
        try {
            assertTrue(OriginalGameFontOverrides.isEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty(OriginalGameFontOverrides.ENABLE_PROPERTY);
            } else {
                System.setProperty(OriginalGameFontOverrides.ENABLE_PROPERTY, previous);
            }
        }
    }

    @Test
    void recognizesExpandedOriginalFontCoverageButStillIgnoresModFonts() {
        assertTrue(OriginalGameFontOverrides.isOverriddenPath("graphics/fonts/insignia15LTaa.fnt"));
        assertTrue(OriginalGameFontOverrides.isOverriddenPath("graphics/fonts/insignia12bold.fnt"));
        assertTrue(OriginalGameFontOverrides.isOverriddenPath("graphics/fonts/insignia15LTaa_0.png"));
        assertTrue(OriginalGameFontOverrides.isOverriddenPath("graphics/fonts/orbitron20bold.fnt"));
        assertTrue(OriginalGameFontOverrides.isOverriddenPath("/graphics/fonts/orbitron20aabold_0.png"));
        assertTrue(OriginalGameFontOverrides.isOverriddenPath("graphics/fonts/victor14.fnt"));
        assertFalse(OriginalGameFontOverrides.isOverriddenPath("graphics/ungp/fonts/ungp_orbitron.fnt"));
    }

    @Test
    void exposesOverrideSpecForKnownOriginalFont() {
        final OriginalGameFontOverrides.FontOverrideSpec spec = OriginalGameFontOverrides.specForPath("graphics/fonts/insignia25LTaa.fnt");
        final OriginalGameFontOverrides.FontProfile activeProfile = OriginalGameFontOverrides.activeProfile();
        assertNotNull(spec);
        assertEquals(activeProfile.insigniaPrimary(), spec.primaryFontCandidates());
        assertEquals(activeProfile.fallback(), spec.fallbackFontCandidates());
    }

    @Test
    void mapsAllManagedOrbitronFontsToBoldPrimaryCandidates() {
        final OriginalGameFontOverrides.FontProfile activeProfile = OriginalGameFontOverrides.activeProfile();

        assertEquals(activeProfile.orbitronBoldPrimary(),
                OriginalGameFontOverrides.specForPath("graphics/fonts/orbitron10.fnt").primaryFontCandidates());
        assertEquals(activeProfile.orbitronBoldPrimary(),
                OriginalGameFontOverrides.specForPath("graphics/fonts/orbitron20aa.fnt").primaryFontCandidates());
        assertEquals(activeProfile.orbitronBoldPrimary(),
                OriginalGameFontOverrides.specForPath("graphics/fonts/orbitron24aabold.fnt").primaryFontCandidates());
    }

    @Test
    void defaultProfilePrefersBundledOriginalTtfFirst() {
        final OriginalGameFontOverrides.FontProfile profile = OriginalGameFontOverrides.activeProfile();
        assertEquals("original-match", profile.name());
        assertEquals("lte50549.ttf", profile.insigniaPrimary().getFirst());
        assertEquals("lte50549.ttf", profile.insigniaBoldPrimary().getFirst());
        assertEquals("orbitron-light.ttf", profile.orbitronRegularPrimary().getFirst());
        assertEquals("orbitron-semibold.ttf", profile.orbitronBoldPrimary().getFirst());
        assertEquals(List.of("Oxanium-Medium.ttf", "MiSans-Regular.ttf"), profile.victorPrimary());
        assertEquals(List.of("MiSans-Regular.ttf"), profile.victorFallback());
        assertEquals(List.of("MiSans-Regular.ttf", "font.ttf"), profile.fallback());
        assertEquals(List.of("MiSans-Regular.ttf", "font.ttf"), profile.boldFallback());
    }

    @Test
    void allowsFontOverridesToBeDisabledExplicitly() {
        final String previous = System.getProperty(OriginalGameFontOverrides.ENABLE_PROPERTY);
        System.setProperty(OriginalGameFontOverrides.ENABLE_PROPERTY, "false");
        try {
            assertFalse(OriginalGameFontOverrides.isEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty(OriginalGameFontOverrides.ENABLE_PROPERTY);
            } else {
                System.setProperty(OriginalGameFontOverrides.ENABLE_PROPERTY, previous);
            }
        }
    }

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