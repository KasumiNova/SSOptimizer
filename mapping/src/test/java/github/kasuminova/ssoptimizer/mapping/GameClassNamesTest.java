package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameClassNamesTest {

    @Test
    void namedConstantsMustStayOutsideSsoptimizerNamespace() {
        assertFalse(GameClassNames.TEXTURED_STRIP_RENDERER.startsWith("github/kasuminova/ssoptimizer/"));
        assertFalse(GameClassNames.COLLISION_GRID_QUERY.startsWith("github/kasuminova/ssoptimizer/"));
        assertFalse(GameClassNames.PARALLEL_IMAGE_PRELOADER.startsWith("github/kasuminova/ssoptimizer/"));
        assertFalse(GameClassNames.TEXT_FIELD_IMPL.startsWith("github/kasuminova/ssoptimizer/"));
        assertFalse(GameClassNames.RESOURCE_LOADER.startsWith("github/kasuminova/ssoptimizer/"));
        assertFalse(GameClassNames.CAMPAIGN_SAVE_PROGRESS_DIALOG.startsWith("github/kasuminova/ssoptimizer/"));
        assertFalse(GameClassNames.SAVE_PROGRESS_OUTPUT_STREAM.startsWith("github/kasuminova/ssoptimizer/"));
        assertTrue(GameClassNames.TEXTURE_LOADER.startsWith("com/fs/graphics/"));
        assertTrue(GameClassNames.STARFARER_SETTINGS.startsWith("com/fs/starfarer/settings/"));
    }
}