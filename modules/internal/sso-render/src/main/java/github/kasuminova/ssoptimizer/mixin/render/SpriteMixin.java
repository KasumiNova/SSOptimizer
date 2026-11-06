package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import com.fs.graphics.util.RenderStateUtils;
import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasTextureResolver;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasUvState;
import github.kasuminova.ssoptimizer.common.render.engine.SpriteRenderHelper;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatchStats;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

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
        if (texture == null) {
            return;
        }
        ((AtlasUvState) this).ssoptimizer$ensureAtlasRemapped("render");
        if (SpriteBatchStats.isEnabled()) {
            SpriteBatchStats.onSpriteRender(AtlasTextureResolver.textureIdForSpriteRender(texture), blendSrc, blendDest, false);
        }
        if (SpriteBatch.getInstance().submitIfActive(AtlasTextureResolver.textureIdForSpriteRender(texture),
                x + offsetX, y + offsetY, width, height, centerX, centerY, angle,
                color.getRed(), color.getGreen(), color.getBlue(),
                (int) (color.getAlpha() * alphaMult), blendSrc, blendDest,
                texX, texY, texWidth, texHeight, texClamp)) {
            return;
        }
        GL11.streamBindTexture(AtlasTextureResolver.textureIdForSpriteRender(texture));
        if (texClamp) {
            RenderStateUtils.enableTextureClamp();
        }
        SpriteRenderHelper.renderSprite(
                x + offsetX, y + offsetY,
                width, height,
                centerX, centerY,
                angle,
                color.getRed(), color.getGreen(), color.getBlue(),
                (int) (color.getAlpha() * alphaMult),
                blendSrc, blendDest,
                texX, texY, texWidth, texHeight);
        if (texClamp) {
            RenderStateUtils.restoreTextureClamp();
        }
    }

    @Overwrite(remap = false)
    public void renderRegion(float f, float g, float h, float i, float j, float k, boolean bl) {
        if (texture == null) {
            return;
        }
        final AtlasUvState atlas = (AtlasUvState) this;
        final boolean remapped = atlas.ssoptimizer$ensureAtlasRemapped("renderRegion");
        final float insetU = remapped ? atlas.ssoptimizer$atlasInsetU() : 0.001F;
        final float insetV = remapped ? atlas.ssoptimizer$atlasInsetV() : 0.001F;

        float u0 = h * texWidth + insetU;
        float v0 = i * texHeight + insetV;
        float u1 = (h + j) * texWidth - insetU;
        float v1 = (i + k) * texHeight - insetV;
        if (remapped) {
            u0 += texX;
            u1 += texX;
            v0 += texY;
            v1 += texY;
        }

        final boolean hasCenter = centerX != -1.0F && centerY != -1.0F;
        final float pivotX = hasCenter ? centerX : width * 0.5F;
        final float pivotY = hasCenter ? centerY : height * 0.5F;
        final float subW = j * width;
        final float subH = k * height;
        final float posX = f + offsetX + (width - subW) * 0.5F;
        final float posY = g + offsetY + (height - subH) * 0.5F;
        final float subCenterX = pivotX - h * width;
        final float subCenterY = pivotY - i * height;
        final int r = color.getRed();
        final int gc = color.getGreen();
        final int b = color.getBlue();
        final int a = (int) (color.getAlpha() * alphaMult);

        if (SpriteBatchStats.isEnabled()) {
            SpriteBatchStats.onSpriteRender(AtlasTextureResolver.textureIdForSpriteRender(texture), blendSrc, blendDest, !bl);
        }
        if (SpriteBatch.getInstance().submitIfActive(AtlasTextureResolver.textureIdForSpriteRender(texture),
                posX, posY, subW, subH, subCenterX, subCenterY, angle,
                r, gc, b, a, blendSrc, blendDest,
                u0, v0, u1 - u0, v1 - v0, false)) {
            return;
        }
        if (bl) {
            GL11.streamBindTexture(AtlasTextureResolver.textureIdForSpriteRender(texture));
        }
        SpriteRenderHelper.renderSprite(
                posX, posY, subW, subH,
                subCenterX, subCenterY,
                angle,
                r, gc, b, a,
                blendSrc, blendDest,
                u0, v0, u1 - u0, v1 - v0);
    }

    @Overwrite(remap = false)
    public void renderNoBind(float x, float y) {
        if (texture == null) {
            return;
        }
        ((AtlasUvState) this).ssoptimizer$ensureAtlasRemapped("renderNoBind");
        if (SpriteBatchStats.isEnabled()) {
            SpriteBatchStats.onSpriteRender(AtlasTextureResolver.textureIdForSpriteRender(texture), blendSrc, blendDest, true);
        }
        if (SpriteBatch.getInstance().submitIfActive(AtlasTextureResolver.textureIdForSpriteRender(texture),
                x + offsetX, y + offsetY, width, height, centerX, centerY, angle,
                color.getRed(), color.getGreen(), color.getBlue(),
                (int) (color.getAlpha() * alphaMult), blendSrc, blendDest,
                texX, texY, texWidth, texHeight, texClamp)) {
            return;
        }
        if (texClamp) {
            RenderStateUtils.enableTextureClamp();
        }
        SpriteRenderHelper.renderSprite(
                x + offsetX, y + offsetY,
                width, height,
                centerX, centerY,
                angle,
                color.getRed(), color.getGreen(), color.getBlue(),
                (int) (color.getAlpha() * alphaMult),
                blendSrc, blendDest,
                texX, texY, texWidth, texHeight);
        if (texClamp) {
            RenderStateUtils.restoreTextureClamp();
        }
    }
}
