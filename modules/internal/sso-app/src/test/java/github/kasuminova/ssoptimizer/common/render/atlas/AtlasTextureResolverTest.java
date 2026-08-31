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

        // 单测环境无 Mixin 注入，TextureObject.getTextureId 为 named jar 原始实现
        // （返回构造时写入的 textureId 字段），即「独立纹理 id」语义的等价验证
        assertEquals(42, AtlasTextureResolver.textureIdForSpriteRender(texture, lookup));
    }
}
