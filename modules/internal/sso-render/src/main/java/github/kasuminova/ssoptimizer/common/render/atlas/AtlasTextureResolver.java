package github.kasuminova.ssoptimizer.common.render.atlas;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.api.loading.WeaponAtlasLookup;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;

public final class AtlasTextureResolver {
    private static volatile WeaponAtlasLookup lookup;

    private AtlasTextureResolver() {
    }

    public static int textureIdForSpriteRender(final TextureObject texture) {
        return textureIdForSpriteRender(texture, atlasLookup());
    }

    public static int textureIdForSpriteRender(final TextureObject texture,
                                               final WeaponAtlasLookup atlasLookup) {
        final WeaponAtlasLookup.Region region = atlasLookup.lookupRegion(texture.getTexturePath());
        if (region != null) {
            return region.textureId();
        }
        return texture.getTextureId();
    }

    private static WeaponAtlasLookup atlasLookup() {
        WeaponAtlasLookup current = lookup;
        if (current == null) {
            current = ServiceRegistry.require(WeaponAtlasLookup.class);
            lookup = current;
        }
        return current;
    }
}
