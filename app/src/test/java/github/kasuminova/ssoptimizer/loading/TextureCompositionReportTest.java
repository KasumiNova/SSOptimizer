package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureCompositionReportTest {
    @Test
    void renderIncludesSummaryGroupsAndDetails() {
        final String report = TextureCompositionReport.render(List.of(
                new TextureCompositionReport.TextureEntry(
                        "graphics/weapons/railgun.png",
                        "resident",
                        true,
                        12,
                        1500,
                        512,
                        512,
                        512,
                        512,
                        1_048_576L,
                        42,
                        "abc123"
                ),
                new TextureCompositionReport.TextureEntry(
                        "graphics/ships/capital.png",
                        "evicted-awaiting-reload",
                        true,
                        1,
                        75_000,
                        2048,
                        2048,
                        2048,
                        2048,
                        16_777_216L,
                        -1,
                        "def456"
                )
        ), Instant.parse("2026-03-29T10:00:00Z"));

        assertTrue(report.contains("[summary]"));
        assertTrue(report.contains("[retention_summary]"));
        assertTrue(report.contains("tracked_textures\t2"));
        assertTrue(report.contains("graphics/weapons"));
        assertTrue(report.contains("graphics/ships"));
        assertTrue(report.contains("required_now"));
        assertTrue(report.contains("not_needed_now"));
        assertTrue(report.contains("railgun.png"));
        assertTrue(report.contains("capital.png"));
    }

    @Test
    void classifyRetentionSeparatesHotResidentAndDeferredAssets() {
        final TextureCompositionReport.RetentionAssessment hotWeapon = TextureCompositionReport.classifyRetention(
                new TextureCompositionReport.TextureEntry(
                        "graphics/weapons/railgun.png",
                        "resident",
                        true,
                        20,
                        1_200,
                        512,
                        512,
                        512,
                        512,
                        1_048_576L,
                        77,
                        "hot"
                )
        );
        final TextureCompositionReport.RetentionAssessment deferredPortrait = TextureCompositionReport.classifyRetention(
                new TextureCompositionReport.TextureEntry(
                        "graphics/portraits/captain.png",
                        "deferred-awaiting-first-bind",
                        true,
                        0,
                        55_000,
                        1024,
                        1024,
                        1024,
                        1024,
                        4_194_304L,
                        -1,
                        "cold"
                )
        );

        assertEquals("required_now", hotWeapon.advice());
        assertEquals("not_needed_now", deferredPortrait.advice());
    }
}