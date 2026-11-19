package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.bridge.opengl.SpriteDraw;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasTextureResolver;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasUvState;
import github.kasuminova.ssoptimizer.common.render.engine.SpriteRenderHelper;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatchStats;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.awt.Color;

@Mixin(targets = GameClassNames.SPRITE_DOTTED)
public abstract class SpriteMixin {

    @Shadow(remap = false)
    protected TextureObject texture;

    @Shadow(remap = false)
    private boolean texClamp;

    @Shadow(remap = false)
    private int offsetX;

    @Shadow(remap = false)
    private int offsetY;

    @Shadow(remap = false)
    protected float width;

    @Shadow(remap = false)
    protected float height;

    @Shadow(remap = false)
    private float centerX;

    @Shadow(remap = false)
    private float centerY;

    @Shadow(remap = false)
    protected float angle;

    @Shadow(remap = false)
    protected Color color;

    @Shadow(remap = false)
    private float alphaMult;

    @Shadow(remap = false)
    private int blendSrc;

    @Shadow(remap = false)
    private int blendDest;

    @Shadow(remap = false)
    protected float texX;

    @Shadow(remap = false)
    protected float texY;

    @Shadow(remap = false)
    protected float texWidth;

    @Shadow(remap = false)
    protected float texHeight;

    @Overwrite(remap = false)
    public void render(float x, float y) {
        ssoptimizer$render(x, y, true);
    }

    @Overwrite(remap = false)
    public void renderNoBind(float x, float y) {
        ssoptimizer$render(x, y, false);
    }

    @Unique
    private void ssoptimizer$render(float x, float y, boolean bindTexture) {
        if (texture == null) {
            return;
        }
        ((AtlasUvState) this).ssoptimizer$ensureAtlasRemapped(bindTexture ? "render" : "renderNoBind");
        ssoptimizer$submit(x, y, bindTexture, texClamp, texX, texY, null);
    }

    @Overwrite(remap = false)
    public void renderRegion(float x, float y, float regionX, float regionY,
                             float regionWidth, float regionHeight, boolean bindTexture) {
        if (texture == null) {
            return;
        }
        AtlasUvState atlas = (AtlasUvState) this;
        boolean remapped = atlas.ssoptimizer$ensureAtlasRemapped("renderRegion");
        float insetU = remapped ? atlas.ssoptimizer$atlasInsetU() : 0.001f;
        float insetV = remapped ? atlas.ssoptimizer$atlasInsetV() : 0.001f;
        ssoptimizer$submit(x, y, bindTexture, false, texX, texY,
                new SpriteDraw.Region(regionX, regionY, regionWidth, regionHeight, insetU, insetV, remapped));
    }

    @Unique
    private void ssoptimizer$submit(float x, float y, boolean bindTexture, boolean clamp,
                                    float u, float v, SpriteDraw.Region region) {
        boolean collectStats = SpriteBatchStats.isEnabled();
        int textureId = bindTexture || collectStats ? AtlasTextureResolver.textureIdForSpriteRender(texture) : 0;
        int src = blendSrc;
        int dest = blendDest;
        if (collectStats) {
            SpriteBatchStats.onSpriteRender(textureId, src, dest, !bindTexture);
        }
        Color drawColor = color;
        int rgba = (drawColor.getRed() << 24) | (drawColor.getGreen() << 16) | (drawColor.getBlue() << 8)
                | ((int) (drawColor.getAlpha() * alphaMult) & 0xff);
        SpriteRenderHelper.renderSprite(new SpriteDraw(textureId, bindTexture, clamp, rgba, src, dest,
                x + offsetX, y + offsetY, width, height, centerX, centerY, angle,
                u, v, texWidth, texHeight, region));
    }
}
