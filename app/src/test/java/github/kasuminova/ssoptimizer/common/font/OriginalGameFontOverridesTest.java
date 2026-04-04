package github.kasuminova.ssoptimizer.common.font;

import org.junit.jupiter.api.Test;

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
        final OriginalGameFontOverrides.FontProfile activeProfile = OriginalGameFontOverrides.resolveProfile(
                OriginalGameFontOverrides.configuredProfileName()
        );
        assertNotNull(spec);
        assertEquals(activeProfile.insigniaPrimary(), spec.primaryFontCandidates());
        assertEquals(activeProfile.fallback(), spec.fallbackFontCandidates());
    }

    @Test
    void mapsAllManagedOrbitronFontsToBoldPrimaryCandidates() {
        final OriginalGameFontOverrides.FontProfile activeProfile = OriginalGameFontOverrides.resolveProfile(
                OriginalGameFontOverrides.configuredProfileName()
        );

        assertEquals(activeProfile.orbitronBoldPrimary(),
                OriginalGameFontOverrides.specForPath("graphics/fonts/orbitron10.fnt").primaryFontCandidates());
        assertEquals(activeProfile.orbitronBoldPrimary(),
                OriginalGameFontOverrides.specForPath("graphics/fonts/orbitron20aa.fnt").primaryFontCandidates());
        assertEquals(activeProfile.orbitronBoldPrimary(),
                OriginalGameFontOverrides.specForPath("graphics/fonts/orbitron24aabold.fnt").primaryFontCandidates());
    }

    @Test
    void resolvesMapleUiVerificationProfile() {
        final OriginalGameFontOverrides.FontProfile profile = OriginalGameFontOverrides.resolveProfile("maple-ui");
        assertEquals("maple-ui", profile.name());
        assertTrue(profile.insigniaPrimary().contains("Maple UI.ttf"));
        assertTrue(profile.insigniaBoldPrimary().contains("Maple UI Bold.ttf"));
        assertTrue(profile.orbitronBoldPrimary().contains("Maple UI Bold.ttf"));
        assertTrue(profile.fallback().contains("MiSans-Medium.ttf"));
    }

    @Test
    void originalMatchProfilePrefersBundledOriginalTtfFirst() {
        final OriginalGameFontOverrides.FontProfile profile = OriginalGameFontOverrides.resolveProfile("original-match");
        assertEquals("lte50549.ttf", profile.insigniaPrimary().getFirst());
        assertEquals("lte50549.ttf", profile.insigniaBoldPrimary().getFirst());
        assertEquals("orbitron-light.ttf", profile.orbitronRegularPrimary().getFirst());
        assertEquals("orbitron-bold.ttf", profile.orbitronBoldPrimary().getFirst());
        assertEquals(List.of("Oxanium-Medium.ttf", "MiSans-Medium.ttf"), profile.victorPrimary());
        assertEquals(List.of("MiSans-Medium.ttf", "font.ttf"), profile.victorFallback());
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
    void baseGenerationScaleKeepsOriginalMetricsEvenWithScreenScaleOverride() {
        final String previous = System.getProperty(EffectiveScreenScale.OVERRIDE_PROPERTY);
        System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, "150%");
        try {
            assertEquals(1.0f, OriginalGameFontOverrides.effectiveBaseGenerationScale());
            assertEquals(1000, OriginalGameFontOverrides.baseScaleBucketMillis("graphics/fonts/orbitron20.fnt"));
        } finally {
            if (previous == null) {
                System.clearProperty(EffectiveScreenScale.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(EffectiveScreenScale.OVERRIDE_PROPERTY, previous);
            }
        }
    }

}