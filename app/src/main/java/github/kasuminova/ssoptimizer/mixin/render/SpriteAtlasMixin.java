package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sprite UV 图集重映射 Mixin。
 * <p>
 * 注入目标：{@code com.fs.graphics.Sprite#setTexture} 与 {@code readResolve}<br>
 * 注入动机：{@link ShipWeaponAtlas} 把舰船/武器贴图合并进 2048² 图集后，Sprite 的
 * 纹理坐标仍指向原始独立纹理的 UV 空间，必须映射进图集区域才能与绑定层的图集
 * 重定向（{@code LazyTextureManager}）配套。<br>
 * 注入效果：setTexture 返回点按贴图路径查图集区域，把 texX/texY/texWidth/texHeight
 * 从「原纹理 GL 空间」换算到「图集 GL 空间」：
 * {@code texX' = (region.x + texX * srcW) / atlasSize}（Y/宽/高同理，srcW/srcH 为
 * 原纹理 GL 尺寸，由 imageWidth/uScale 推得）。readResolve（XStream 反序列化恢复
 * 原始空间 UV）同样重映射。sprite.texture 引用保持原对象（imageWidth/平均色等
 * 元数据消费者不受影响）。
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

    /**
     * @author KasumiNova
     * @reason 已入图集的贴图在 setTexture 时把 UV 映射进图集区域。
     */
    @Inject(method = "setTexture", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlas(final TextureObject newTexture, final CallbackInfo ci) {
        if (newTexture != null) {
            ssoptimizer$remap(newTexture);
        }
    }

    /**
     * @author KasumiNova
     * @reason 反序列化恢复的 Sprite 不经过 setTexture，UV 为原始空间，需同样重映射。
     */
    @Inject(method = "readResolve", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlasAfterDeserialize(final CallbackInfoReturnable<Object> cir) {
        if (this.texture != null) {
            ssoptimizer$remap(this.texture);
        }
    }

    /**
     * 把当前 UV 四字段从原纹理 GL 空间换算到图集 GL 空间。
     * 原纹理 GL 尺寸 = imageSize / uvScale（uScale = imageWidth / textureWidth）。
     */
    private void ssoptimizer$remap(final TextureObject source) {
        final ShipWeaponAtlas.Region region = ShipWeaponAtlas.lookup(source.getTexturePath());
        if (region == null) {
            return;
        }
        final float srcW = source.getImageWidth() / source.getUScale();
        final float srcH = source.getImageHeight() / source.getVScale();
        final float atlasSize = region.atlasSize();
        this.texX = (region.x() + this.texX * srcW) / atlasSize;
        this.texY = (region.y() + this.texY * srcH) / atlasSize;
        this.texWidth = this.texWidth * srcW / atlasSize;
        this.texHeight = this.texHeight * srcH / atlasSize;
    }
}
