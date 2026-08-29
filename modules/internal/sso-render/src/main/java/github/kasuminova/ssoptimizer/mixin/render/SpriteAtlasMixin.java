package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasUvMapper;
import github.kasuminova.ssoptimizer.common.render.atlas.AtlasUvState;
import github.kasuminova.ssoptimizer.api.loading.WeaponAtlasLookup;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
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
 * 注入动机：ShipWeaponAtlas（loading 域，经 WeaponAtlasLookup 接口访问）把舰船/武器贴图合并进图集后，Sprite 的
 * 纹理坐标仍指向原始独立纹理的 UV 空间，必须映射进图集区域才能与绑定层的图集
 * 重定向（{@code LazyTextureManager}）配套。<br>
 * 注入效果：
 * <ol>
 *   <li>{@code setTexture}/{@code readResolve}（XStream 反序列化恢复原始空间 UV）
 *       返回点按贴图路径查图集区域，把 texX/texY/texWidth/texHeight 从「原纹理 GL
 *       空间」换算到「图集 GL 空间」：
 *       {@code texX' = (region.x + texX * srcW) / atlasSize}（Y/宽/高同理，
 *       srcW/srcH 为原纹理 GL 尺寸，由 imageWidth/uScale 推得），并置重映射标记；
 *       sprite.texture 引用保持原对象（imageWidth/平均色等元数据消费者不受影响）。</li>
 *   <li>同贴图重复 {@code setTexture} 的<b>幂等重推导</b>：原版 setTexture
 *       只重置 texWidth/texHeight、不重置 texX/texY，重复调用时 texX/texY 仍
 *       是上次换算后的图集值，若继续按当前值换算会二次平移进入相邻图集
 *       Region（实机触发点：Ship.shadow.setTexture 在动画路径二次调用，舰船
 *       图标串图）。本 Mixin 缓存「首次换算时的原始 texX/texY/texWidth/texHeight
 *       + 原贴图路径」，同贴图重复 setTexture 且 texX/texY 未被 setTexX/setTexY
 *       改动时从缓存原始值重新推导（结果与首次一致）；<b>换贴图</b>（新贴图
 *       路径与缓存不同）时当前 texX/texY 是旧贴图的图集空间值，先把它们复位
 *       为原版默认 (0,0) 再建立新基准（否则旧图集偏移会被当成新贴图的原始
 *       UV，二次平移进相邻 Region）；落到非图集纹理 / 解绑时重置缓存
 *       （见 {@link AtlasUvMapper} 的幂等性契约）。</li>
 *   <li>{@code renderNoBlendOrRotate}/{@code renderAtCenterWithCornerColors}
 *       两个方法的 {@code glTexCoord2f} 调用对<b>已重映射</b>的精灵补上
 *       texX/texY 原点偏移——原版这两个方法假设 UV 原点为 (0,0)
 *       （原版 texX/texY 恒为 0 时行为不变），图集化后原点必须加上区域偏移，
 *       否则会渲染图集左下角内容。{@code renderRegion} 由 {@link SpriteMixin}
 *       整体覆写为合批/单 JNI 路径，图集原点与边缘内缩在覆写方法内联处理。</li>
 * </ol>
 */
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

    /** 当前纹理是否已重映射进图集（决定原点假设方法是否补偏移）。 */
    @Unique
    private transient boolean ssoptimizer$atlasRemapped;

    /** 图集化后与原 UV 域 0.001F 像素等价的 U 向内缩（0.001 * srcW / atlasSize）。 */
    @Unique
    private transient float ssoptimizer$atlasInsetU;

    /** 图集化后与原 UV 域 0.001F 像素等价的 V 向内缩（0.001 * srcH / atlasSize）。 */
    @Unique
    private transient float ssoptimizer$atlasInsetV;

    // ── 幂等重推导缓存（同贴图重复 setTexture 的 UV 二次平移防护，见类 javadoc）──
    // 全部 transient：Sprite 会被战役存档 XStream 序列化（save 配置含 Sprite alias），
    // 注入字段均为运行期派生状态；读档后 readResolve 注入点无条件重推导
    // （序列化值本就会被该 hook 覆盖，持久化纯属污染存档）。
    /** 幂等缓存是否已建立（存在「原始 UV 四元组 + 原贴图路径」基准）。 */
    @Unique
    private transient boolean ssoptimizer$atlasOriginCached;
    /** 幂等基准：首次换算时的原始纹理空间 texX（缓存建立时的当前值）。 */
    @Unique
    private transient float ssoptimizer$atlasOriginTexX;
    /** 幂等基准：首次换算时的原始纹理空间 texY。 */
    @Unique
    private transient float ssoptimizer$atlasOriginTexY;
    /** 幂等基准：首次换算时的原始纹理空间 texWidth（原版 setTexture 重置后的值）。 */
    @Unique
    private transient float ssoptimizer$atlasOriginTexWidth;
    /** 幂等基准：首次换算时的原始纹理空间 texHeight。 */
    @Unique
    private transient float ssoptimizer$atlasOriginTexHeight;
    /** 幂等基准：原贴图路径（同贴图重复 setTexture 的标识）。 */
    @Unique
    private transient String ssoptimizer$atlasOriginTexturePath;
    /** 上次换算产出的图集 texX（判定 texX/texY 是否被 setTexX/setTexY 改动过）。 */
    @Unique
    private transient float ssoptimizer$atlasLastTexX;
    /** 上次换算产出的图集 texY。 */
    @Unique
    private transient float ssoptimizer$atlasLastTexY;

    /**
     * @author KasumiNova
     * @reason 已入图集的贴图在 setTexture 时把 UV 映射进图集区域；同贴图重复
     * setTexture 从幂等缓存原始值重新推导（原版只重置 texWidth/texHeight、
     * 不重置 texX/texY，按当前图集值再换算会二次平移——串图根因）。
     */
    @Inject(method = "setTexture", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlas(final TextureObject newTexture, final CallbackInfo ci) {
        if (newTexture == null) {
            // 解绑纹理：重映射状态与幂等缓存一并清除
            this.ssoptimizer$atlasRemapped = false;
            this.ssoptimizer$clearAtlasOriginCache();
            return;
        }
        if (this.ssoptimizer$atlasOriginCached
                && this.ssoptimizer$atlasOriginTexturePath.equals(newTexture.getTexturePath())
                && this.texX == this.ssoptimizer$atlasLastTexX
                && this.texY == this.ssoptimizer$atlasLastTexY) {
            // 同贴图重复 setTexture 且 texX/texY 未被 setTexX/setTexY 改动：
            // 当前 texX/texY 仍是上次换算后的图集值（原版不重置），必须从缓存
            // 原始值重新推导；texWidth/texHeight 已由原版重置为原始空间值，
            // 与缓存基准一致
            this.ssoptimizer$atlasRemapped = this.ssoptimizer$remapFromOrigin(newTexture);
            return;
        }
        // 首次 / 换贴图 / texX/texY 被 setTexX/setTexY 改过：texWidth/texHeight
        // 刚被原版重置为原始空间值，texX/texY 为原始空间值（换贴图时若遗留
        // 旧图集值，是原版「setTexture 不重置 texX/texY」的既有语义，调用方
        // 负责）——缓存当前四元组作为新的原始基准后换算。
        // 换贴图特判：若幂等缓存持有的是<b>另一张贴图</b>（缓存存在即旧贴图
        // 曾命中图集并完成重映射），当前 texX/texY 是旧贴图的图集空间值——
        // 直接作为新贴图的「原始基准」会在换算时二次平移进入相邻图集 Region
        // （修复前 setTexture 幂等化只覆盖了同贴图重复调用，换贴图路径漏网）。
        // 原版 setTexture 不重置 texX/texY，全贴图精灵的默认原点就是 (0,0)；
        // 先复位再建基准，与「新精灵首次 setTexture」的结果一致。
        if (this.ssoptimizer$atlasOriginCached
                && !this.ssoptimizer$atlasOriginTexturePath.equals(newTexture.getTexturePath())) {
            this.texX = 0.0F;
            this.texY = 0.0F;
        }
        this.ssoptimizer$atlasRemapped = this.ssoptimizer$cacheOriginAndRemap(newTexture);
    }

    /**
     * @author KasumiNova
     * @reason 反序列化恢复的 Sprite 不经过 setTexture，UV 为原始空间，需同样重映射。
     */
    @Inject(method = "readResolve", at = @At("RETURN"), remap = false)
    private void ssoptimizer$remapToAtlasAfterDeserialize(final CallbackInfoReturnable<Object> cir) {
        if (this.texture == null) {
            this.ssoptimizer$atlasRemapped = false;
            this.ssoptimizer$clearAtlasOriginCache();
            return;
        }
        // 新反序列化对象无缓存：以当前 UV（原始空间）建立基准后换算
        this.ssoptimizer$atlasRemapped = this.ssoptimizer$cacheOriginAndRemap(this.texture);
    }

    /**
     * @author KasumiNova
     * @reason renderNoBlendOrRotate/renderAtCenterWithCornerColors 的
     * UV 计算假设原点 (0,0)，图集化后必须补区域原点偏移；未重映射的精灵保持原样
     * （原版行为对 setTexX 后的精灵同样忽略 texX，不擅自改变）。
     * renderRegion 由 SpriteMixin 覆写后不再包含 glTexCoord2f 调用，不在此处理。
     * require=0：分离模式下调用点已被 ASM 重定向到 bridge owner，由成对的
     * {@link #ssoptimizer$texCoordWithAtlasOriginBridged} 命中。
     */
    @Redirect(method = {"renderNoBlendOrRotate(FFZ)V", "renderAtCenterWithCornerColors(FF)V"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexCoord2f(FF)V"),
            remap = false, require = 0)
    private void ssoptimizer$texCoordWithAtlasOrigin(final float u, final float v) {
        if (this.ssoptimizer$atlasRemapped) {
            GL11.glTexCoord2f(u + this.texX, v + this.texY);
        } else {
            GL11.glTexCoord2f(u, v);
        }
    }

    /**
     * 分离模式锚点：调用点被 RenderThreadRedirectTransformer 改写为 bridge
     * {@code GL11.glTexCoord2f} 后由本 @Redirect 命中，handler 复用同一实现
     * （其内部的 GL11 调用在分离模式下同样经本类字节码的 owner 改写进入录制）。
     */
    @Redirect(method = {"renderNoBlendOrRotate(FFZ)V", "renderAtCenterWithCornerColors(FF)V"},
            at = @At(value = "INVOKE",
                    target = "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GL11;glTexCoord2f(FF)V"),
            remap = false, require = 0)
    private void ssoptimizer$texCoordWithAtlasOriginBridged(final float u, final float v) {
        ssoptimizer$texCoordWithAtlasOrigin(u, v);
    }

    /**
     * 供 {@link SpriteMixin} 的 renderRegion 覆写读取重映射标记
     * （Mixin 包不被 LaunchClassLoader 加载，经 {@link AtlasUvState} 接口注入传递）。
     */
    @Override
    public boolean ssoptimizer$isAtlasRemapped() {
        return this.ssoptimizer$atlasRemapped;
    }

    /** 供 {@link SpriteMixin} 的 renderRegion 覆写读取像素等价 U 内缩。 */
    @Override
    public float ssoptimizer$atlasInsetU() {
        return this.ssoptimizer$atlasInsetU;
    }

    /** 供 {@link SpriteMixin} 的 renderRegion 覆写读取像素等价 V 内缩。 */
    @Override
    public float ssoptimizer$atlasInsetV() {
        return this.ssoptimizer$atlasInsetV;
    }

    /**
     * 把当前 UV 四字段作为「原始纹理 GL 空间」基准缓存，并换算到图集 GL 空间。
     * 供 setTexture/readResolve 注入点的首次换算与换贴图路径调用。
     *
     * @param source 当前贴图（调用点已判非 null）
     * @return 命中图集并完成重映射返回 true
     */
    private boolean ssoptimizer$cacheOriginAndRemap(final TextureObject source) {
        this.ssoptimizer$atlasOriginCached = true;
        this.ssoptimizer$atlasOriginTexturePath = source.getTexturePath();
        this.ssoptimizer$atlasOriginTexX = this.texX;
        this.ssoptimizer$atlasOriginTexY = this.texY;
        this.ssoptimizer$atlasOriginTexWidth = this.texWidth;
        this.ssoptimizer$atlasOriginTexHeight = this.texHeight;
        return this.ssoptimizer$remapFromOrigin(source);
    }

    /** 清除幂等缓存（解绑纹理 / 落到非图集纹理时调用，避免陈旧基准误导后续重推导）。 */
    private void ssoptimizer$clearAtlasOriginCache() {
        this.ssoptimizer$atlasOriginCached = false;
        this.ssoptimizer$atlasOriginTexturePath = null;
    }

    /**
     * 从幂等缓存中的原始 UV 四元组换算到图集 GL 空间（计算本体委托
     * {@link AtlasUvMapper#remapFromOrigin}，见其幂等性契约）。
     *
     * @param source 当前贴图（调用点已判非 null）
     * @return 命中图集并完成重映射返回 true
     */
    private boolean ssoptimizer$remapFromOrigin(final TextureObject source) {
        final WeaponAtlasLookup.Region region = ServiceRegistry.require(WeaponAtlasLookup.class)
                .lookupRegion(source.getTexturePath());
        if (region == null) {
            // 未入图集：维持原始 UV，并清除缓存——后续同贴图 setTexture 时
            // 重新从当前值评估（图集在运行时才构建完成，加载早期贴图可能先
            // 未入图集后入图集）
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
        // 原版 renderRegion 的 0.001F 边缘内缩以原纹理 UV 域为基准（= 0.001 * srcW 像素），
        // 换算到图集 UV 域保持像素等价
        this.ssoptimizer$atlasInsetU = uv.insetU();
        this.ssoptimizer$atlasInsetV = uv.insetV();
        // 记录本次产出的图集 texX/texY：下次 setTexture 判定 texX/texY 是否被
        // setTexX/setTexY 改动过（未改动才走幂等路径）
        this.ssoptimizer$atlasLastTexX = this.texX;
        this.ssoptimizer$atlasLastTexY = this.texY;
        return true;
    }
}
