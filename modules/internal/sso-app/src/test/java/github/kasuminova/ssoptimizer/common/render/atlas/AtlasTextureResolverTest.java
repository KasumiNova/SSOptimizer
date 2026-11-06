package github.kasuminova.ssoptimizer.common.render.atlas;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.api.loading.WeaponAtlasLookup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtlasTextureResolverTest {

    @Test
    void returnsAtlasPageIdWhenPathIsInAtlas() {
        final TextureObject texture = new TextureObject(3553, 42, "graphics/ships/frigate.png");
        final WeaponAtlasLookup lookup = path -> new WeaponAtlasLookup.Region(777, 8192, 64, 128);

        assertEquals(777, AtlasTextureResolver.textureIdForSpriteRender(texture, lookup));
    }

    @Test
    void fallsBackToStandaloneLazyIdWhenPathIsNotInAtlas() {
        final TextureObject texture = new TextureObject(3553, 42, "graphics/ships/frigate.png");
        final WeaponAtlasLookup lookup = path -> null;

        assertEquals(42, AtlasTextureResolver.textureIdForSpriteRender(texture, lookup));
    }
}
