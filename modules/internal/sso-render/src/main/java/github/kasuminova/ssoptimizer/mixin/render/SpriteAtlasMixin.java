package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasRenderDiag;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasUvMapper;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasUvState;
import github.kasuminova.ssoptimizer.api.loading.WeaponAtlasLookup;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AtlasUvState 用于跨 Mixin 传递图集状态，避免直接引用 Mixin 类。 */
@Mixin(targets = GameClassNames.SPRITE_DOTTED)
public abstract class SpriteAtlasMixin implements AtlasUvState {
    @Shadow(remap = false)
    protected float texX;

    @Shadow(remap = false)
    protected float texY;

    @Shadow(remap = false)
    protected float texWidth;

    @Shadow(remap = false)
    protected float texHeight;

    @Shadow(remap = false)
    protected TextureObject texture;

    // 图集派生状态不参与 XStream 持久化。
    @Unique
    private transient boolean ssoptimizer$atlasRemapped;

    @Unique
    private transient float ssoptimizer$atlasInsetU;

    @Unique
    private transient float ssoptimizer$atlasInsetV;

    @Unique
    private transient boolean ssoptimizer$atlasOriginCached;
    @Unique
    private transient float ssoptimizer$atlasOriginTexX;
    @Unique
    private transient float ssoptimizer$atlasOriginTexY;
    @Unique
    private transient float ssoptimizer$atlasOriginTexWidth;
    @Unique
    private transient float ssoptimizer$atlasOriginTexHeight;
    @Unique
    private transient String ssoptimizer$atlasOriginTexturePath;
    @Unique
    private transient float ssoptimizer$atlasLastTexX;
    @Unique
    private transient float ssoptimizer$atlasLastTexY;

    @Unique
    private transient float ssoptimizer$atlasOriginU;
    @Unique
    private transient float ssoptimizer$atlasOriginV;
    @Unique
    private transient float ssoptimizer$atlasScaleU;
    @Unique
    private transient float ssoptimizer$atlasScaleV;

    @Unique
    private transient String ssoptimizer$diagCheckedPath;
    @Unique
    private transient boolean ssoptimizer$diagAtlased;

    @Inject(method = "setTexture", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlas(final TextureObject newTexture, final CallbackInfo ci) {
        if (newTexture == null) {
            this.ssoptimizer$atlasRemapped = false;
            this.ssoptimizer$clearAtlasOriginCache();
            return;
        }
        if (this.ssoptimizer$atlasOriginCached
                && this.ssoptimizer$atlasOriginTexturePath.equals(newTexture.getTexturePath())
                && this.texX == this.ssoptimizer$atlasLastTexX
                && this.texY == this.ssoptimizer$atlasLastTexY) {
            // 同贴图重复设置时从原始 UV 重算，避免二次平移。
            this.ssoptimizer$atlasRemapped = this.ssoptimizer$remapFromOrigin(newTexture);
            return;
        }
        // 换贴图时旧图集原点不能作为新贴图的原始 UV。
        if (this.ssoptimizer$atlasOriginCached
                && !this.ssoptimizer$atlasOriginTexturePath.equals(newTexture.getTexturePath())) {
            this.texX = 0.0F;
            this.texY = 0.0F;
        }
        this.ssoptimizer$atlasRemapped = this.ssoptimizer$cacheOriginAndRemap(newTexture);
    }

    @Inject(method = "readResolve", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlasAfterDeserialize(final CallbackInfoReturnable<Object> cir) {
        if (this.texture == null) {
            this.ssoptimizer$atlasRemapped = false;
            this.ssoptimizer$clearAtlasOriginCache();
            return;
        }
        this.ssoptimizer$atlasRemapped = this.ssoptimizer$cacheOriginAndRemap(this.texture);
    }

    @Inject(method = "renderWithCorners(FFFFFFFF)V", at = @At("HEAD"), remap = false)
    private void ssoptimizer$healBeforeRenderWithCorners(final float x1, final float y1,
                                                         final float x2, final float y2,
                                                         final float x3, final float y3,
                                                         final float x4, final float y4,
                                                         final CallbackInfo ci) {
        ssoptimizer$ensureAtlasRemapped("renderWithCorners");
    }

    @Redirect(method = {"renderNoBlendOrRotate(FFZ)V", "renderAtCenterWithCornerColors(FF)V"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexCoord2f(FF)V"),
            remap = false, require = 0)
    private void ssoptimizer$texCoordWithAtlasOrigin(final float u, final float v) {
        if (this.ssoptimizer$ensureAtlasRemapped("texCoordOriginFixup")) {
            GL11.glTexCoord2f(u + this.texX, v + this.texY);
        } else {
            GL11.glTexCoord2f(u, v);
        }
    }

    // 分离模式的调用点由 ASM 重定向到 bridge GL11。
    @Redirect(method = {"renderNoBlendOrRotate(FFZ)V", "renderAtCenterWithCornerColors(FF)V"},
            at = @At(value = "INVOKE",
                    target = "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GL11;glTexCoord2f(FF)V"),
            remap = false, require = 0)
    private void ssoptimizer$texCoordWithAtlasOriginBridged(final float u, final float v) {
        ssoptimizer$texCoordWithAtlasOrigin(u, v);
    }

    @Override
    public boolean ssoptimizer$isAtlasRemapped() {
        return this.ssoptimizer$atlasRemapped;
    }

    @Override
    public float ssoptimizer$atlasInsetU() {
        return this.ssoptimizer$atlasInsetU;
    }

    @Override
    public float ssoptimizer$atlasInsetV() {
        return this.ssoptimizer$atlasInsetV;
    }

    @Override
    public boolean ssoptimizer$ensureAtlasRemapped(final String renderMethod) {
        if (this.ssoptimizer$atlasRemapped) {
            return true;
        }
        if (this.texture == null) {
            return false;
        }
        final String path = this.texture.getTexturePath();
        if (path == null) {
            return false;
        }
        if (!path.equals(this.ssoptimizer$diagCheckedPath)) {
            final WeaponAtlasLookup lookup = ServiceRegistry.getOrNull(WeaponAtlasLookup.class);
            final boolean atlased = lookup != null && lookup.lookupRegion(path) != null;
            // 加载完成前的未命中不缓存，避免遮蔽随后创建的图集。
            if (atlased || RenderThreadMode.isLoadingFinished()) {
                this.ssoptimizer$diagCheckedPath = path;
                this.ssoptimizer$diagAtlased = atlased;
            }
            if (!atlased) {
                return false;
            }
        } else if (!this.ssoptimizer$diagAtlased) {
            return false;
        }
        AtlasRenderDiag.warnUnremappedSample(path, renderMethod,
                this.texX, this.texY, this.texWidth, this.texHeight);
        this.ssoptimizer$atlasRemapped = this.ssoptimizer$cacheOriginAndRemap(this.texture);
        return this.ssoptimizer$atlasRemapped;
    }

    private boolean ssoptimizer$cacheOriginAndRemap(final TextureObject source) {
        this.ssoptimizer$atlasOriginCached = true;
        this.ssoptimizer$atlasOriginTexturePath = source.getTexturePath();
        this.ssoptimizer$atlasOriginTexX = this.texX;
        this.ssoptimizer$atlasOriginTexY = this.texY;
        this.ssoptimizer$atlasOriginTexWidth = this.texWidth;
        this.ssoptimizer$atlasOriginTexHeight = this.texHeight;
        return this.ssoptimizer$remapFromOrigin(source);
    }

    private void ssoptimizer$clearAtlasOriginCache() {
        this.ssoptimizer$atlasOriginCached = false;
        this.ssoptimizer$atlasOriginTexturePath = null;
    }

    private boolean ssoptimizer$remapFromOrigin(final TextureObject source) {
        final WeaponAtlasLookup.Region region = ServiceRegistry.require(WeaponAtlasLookup.class)
                .lookupRegion(source.getTexturePath());
        if (region == null) {
            this.ssoptimizer$clearAtlasOriginCache();
            return false;
        }
        final float srcW = source.getImageWidth() / source.getUScale();
        final float srcH = source.getImageHeight() / source.getVScale();
        final AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                this.ssoptimizer$atlasOriginTexX, this.ssoptimizer$atlasOriginTexY,
                this.ssoptimizer$atlasOriginTexWidth, this.ssoptimizer$atlasOriginTexHeight,
                srcW, srcH, region.x(), region.y(), region.atlasSize());
        this.texX = uv.texX();
        this.texY = uv.texY();
        this.texWidth = uv.texWidth();
        this.texHeight = uv.texHeight();
        this.ssoptimizer$atlasInsetU = uv.insetU();
        this.ssoptimizer$atlasInsetV = uv.insetV();
        this.ssoptimizer$atlasOriginU = uv.originU();
        this.ssoptimizer$atlasOriginV = uv.originV();
        this.ssoptimizer$atlasScaleU = uv.scaleU();
        this.ssoptimizer$atlasScaleV = uv.scaleV();
        this.ssoptimizer$atlasLastTexX = this.texX;
        this.ssoptimizer$atlasLastTexY = this.texY;
        return true;
    }

    @Overwrite(remap = false)
    public void setTexX(final float x) {
        if (this.ssoptimizer$atlasRemapped) {
            this.ssoptimizer$atlasOriginTexX = x;
            this.texX = AtlasUvMapper.originalToAtlas(
                    this.ssoptimizer$atlasOriginU, this.ssoptimizer$atlasScaleU, x);
            this.ssoptimizer$atlasLastTexX = this.texX;
        } else {
            this.texX = x;
        }
    }

    @Overwrite(remap = false)
    public void setTexY(final float y) {
        if (this.ssoptimizer$atlasRemapped) {
            this.ssoptimizer$atlasOriginTexY = y;
            this.texY = AtlasUvMapper.originalToAtlas(
                    this.ssoptimizer$atlasOriginV, this.ssoptimizer$atlasScaleV, y);
            this.ssoptimizer$atlasLastTexY = this.texY;
        } else {
            this.texY = y;
        }
    }

    @Overwrite(remap = false)
    public void setTexWidth(final float width) {
        if (this.ssoptimizer$atlasRemapped) {
            this.ssoptimizer$atlasOriginTexWidth = width;
            this.texWidth = AtlasUvMapper.originalToAtlas(0.0F, this.ssoptimizer$atlasScaleU, width);
        } else {
            this.texWidth = width;
        }
    }

    @Overwrite(remap = false)
    public void setTexHeight(final float height) {
        if (this.ssoptimizer$atlasRemapped) {
            this.ssoptimizer$atlasOriginTexHeight = height;
            this.texHeight = AtlasUvMapper.originalToAtlas(0.0F, this.ssoptimizer$atlasScaleV, height);
        } else {
            this.texHeight = height;
        }
    }

    @Overwrite(remap = false)
    public float getTexX() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(
                    this.ssoptimizer$atlasOriginU, this.ssoptimizer$atlasScaleU, this.texX);
        }
        return this.texX;
    }

    @Overwrite(remap = false)
    public float getTexY() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(
                    this.ssoptimizer$atlasOriginV, this.ssoptimizer$atlasScaleV, this.texY);
        }
        return this.texY;
    }

    @Overwrite(remap = false)
    public float getTexWidth() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(0.0F, this.ssoptimizer$atlasScaleU, this.texWidth);
        }
        return this.texWidth;
    }

    @Overwrite(remap = false)
    public float getTexHeight() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(0.0F, this.ssoptimizer$atlasScaleV, this.texHeight);
        }
        return this.texHeight;
    }
}
