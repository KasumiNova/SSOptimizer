package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sprite UV 图集重映射 Mixin。
 * <p>
 * 注入目标：{@code com.fs.graphics.Sprite}<br>
 * 注入动机：{@link ShipWeaponAtlas} 把舰船/武器贴图合并进图集后，Sprite 的
 * 纹理坐标仍指向原始独立纹理的 UV 空间，必须映射进图集区域才能与绑定层的图集
 * 重定向（{@code LazyTextureManager}）配套。<br>
 * 注入效果：
 * <ol>
 *   <li>{@code setTexture}/{@code readResolve}（XStream 反序列化恢复原始空间 UV）
 *       返回点按贴图路径查图集区域，把 texX/texY/texWidth/texHeight 从「原纹理 GL
 *       空间」换算到「图集 GL 空间」：
 *       {@code texX' = (region.x + texX * srcW) / atlasSize}（Y/宽/高同理，
 *       srcW/srcH 为原纹理 GL 尺寸，由 imageWidth/uScale 推得），并置重映射标记；
 *       sprite.texture 引用保持原对象（imageWidth/平均色等元数据消费者不受影响）；</li>
 *   <li>{@code renderRegion}/{@code renderNoBlendOrRotate}/
 *       {@code renderAtCenterWithCornerColors} 三个方法的 {@code glTexCoord2f}
 *       调用对<b>已重映射</b>的精灵补上 texX/texY 原点偏移——原版这三个方法
 *       假设 UV 原点为 (0,0)（原版 texX/texY 恒为 0 时行为不变），图集化后
 *       原点必须加上区域偏移，否则会渲染图集左下角内容。</li>
 * </ol>
 */
@Mixin(targets = GameClassNames.SPRITE_DOTTED)
public abstract class SpriteAtlasMixin {
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

    /** 当前纹理是否已重映射进图集（决定三个原点假设方法是否补偏移）。 */
    @Unique
    private boolean ssoptimizer$atlasRemapped;

    /**
     * @author KasumiNova
     * @reason 已入图集的贴图在 setTexture 时把 UV 映射进图集区域。
     */
    @Inject(method = "setTexture", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlas(final TextureObject newTexture, final CallbackInfo ci) {
        this.ssoptimizer$atlasRemapped = newTexture != null && ssoptimizer$remap(newTexture);
    }

    /**
     * @author KasumiNova
     * @reason 反序列化恢复的 Sprite 不经过 setTexture，UV 为原始空间，需同样重映射。
     */
    @Inject(method = "readResolve", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlasAfterDeserialize(final CallbackInfoReturnable<Object> cir) {
        this.ssoptimizer$atlasRemapped = this.texture != null && ssoptimizer$remap(this.texture);
    }

    /**
     * @author KasumiNova
     * @reason renderRegion/renderNoBlendOrRotate/renderAtCenterWithCornerColors 的
     * UV 计算假设原点 (0,0)，图集化后必须补区域原点偏移；未重映射的精灵保持原样
     * （原版行为对 setTexX 后的精灵同样忽略 texX，不擅自改变）。
     */
    @Redirect(method = {"renderRegion(FFFFFFZ)V", "renderNoBlendOrRotate(FFZ)V", "renderAtCenterWithCornerColors(FF)V"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexCoord2f(FF)V"),
            remap = false)
    private void ssoptimizer$texCoordWithAtlasOrigin(final float u, final float v) {
        if (this.ssoptimizer$atlasRemapped) {
            GL11.glTexCoord2f(u + this.texX, v + this.texY);
        } else {
            GL11.glTexCoord2f(u, v);
        }
    }

    /**
     * 把当前 UV 四字段从原纹理 GL 空间换算到图集 GL 空间。
     * 原纹理 GL 尺寸 = imageSize / uvScale（uScale = imageWidth / textureWidth）。
     *
     * @return 命中图集并完成重映射返回 true
     */
    private boolean ssoptimizer$remap(final TextureObject source) {
        final ShipWeaponAtlas.Region region = ShipWeaponAtlas.lookup(source.getTexturePath());
        if (region == null) {
            return false;
        }
        final float srcW = source.getImageWidth() / source.getUScale();
        final float srcH = source.getImageHeight() / source.getVScale();
        final float atlasSize = region.atlasSize();
        this.texX = (region.x() + this.texX * srcW) / atlasSize;
        this.texY = (region.y() + this.texY * srcH) / atlasSize;
        this.texWidth = this.texWidth * srcW / atlasSize;
        this.texHeight = this.texHeight * srcH / atlasSize;
        return true;
    }
}
