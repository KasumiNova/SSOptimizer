package github.kasuminova.ssoptimizer.common.render.atlas;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShipWeaponAtlasTest {

    @Test
    void extractsStringAndArraySpritePathsFromGraphicsSection() throws JSONException {
        final JSONObject settings = new JSONObject("{"
                + "\"graphics\": {"
                + "  \"textures\": {"
                + "    \"BUtil_NONE\": \"graphics/textures/BUtil_NONE.png\","
                + "    \"cycling\": [\"graphics/fx/a.png\", \"graphics/fx/b.png\"]"
                + "  },"
                + "  \"icons\": { \"cargo\": \"graphics/icons/cargo.png\" }"
                + "},"
                + "\"otherSection\": { \"notGraphics\": \"graphics/ships/frigate.png\" }"
                + "}");

        final Set<String> paths = ShipWeaponAtlas.extractGraphicsSpritePaths(settings);

        assertEquals(Set.of(
                "graphics/textures/BUtil_NONE.png",
                "graphics/fx/a.png",
                "graphics/fx/b.png",
                "graphics/icons/cargo.png"), paths);
    }

    @Test
    void missingGraphicsSectionYieldsEmptySet() throws JSONException {
        assertTrue(ShipWeaponAtlas.extractGraphicsSpritePaths(new JSONObject("{}")).isEmpty());
    }

    @Test
    void nonObjectCategoriesAndNonStringValuesAreIgnored() throws JSONException {
        final JSONObject settings = new JSONObject("{"
                + "\"graphics\": {"
                + "  \"flatArray\": [\"graphics/fx/ignored.png\"],"
                + "  \"cat\": { \"num\": 42, \"real\": \"graphics/fx/real.png\" }"
                + "}"
                + "}");

        assertEquals(Set.of("graphics/fx/real.png"),
                ShipWeaponAtlas.extractGraphicsSpritePaths(settings));
    }
}
