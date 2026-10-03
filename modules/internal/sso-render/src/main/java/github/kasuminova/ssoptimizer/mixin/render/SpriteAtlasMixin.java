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
 *   <li>UV 访问器（{@code setTexX/setTexY/setTexWidth/setTexHeight} 与对应
 *       getter）<b>透明换算</b>：原版语义下 UV 域即整张独立纹理，模组
 *       （实机案例：LWE 机甲 hullmod {@code lwe_gear_control} 逐帧
 *       {@code setTexWidth(0/1.0)} 切换武器显隐）读写的都是原始空间值；
 *       不换算则写入的 1.0 会以图集页为采样域（采满整页宽度，串图/拉伸条纹）。
 *       覆写后 setter 把原始空间值换算进图集区域并同步幂等基准缓存（保持
 *       setTexture 幂等重推导语义），getter 反向换算回原始空间（读写往返
 *       恒等）；换算参数（region 原点 + 缩放）在重映射时由
 *       {@link AtlasUvMapper.RemappedUv} 一并产出。内部渲染路径直接读字段
 *       不经访问器，不受换算影响。</li>
 *   <li>渲染路径入口的<b>惰性治愈</b>（{@link #ssoptimizer$ensureAtlasRemapped}）：
 *       图集构建（{@code ResourceLoaderState.init} 返回点）前创建并被缓存的
 *       陈旧 Sprite 此后不再经历 setTexture，永远停留在未重映射状态，渲染时
 *       以原始空间 UV 采样整页图集（缩略图串图/条纹的根因类别）。实例持有者
 *       分散且不可枚举，故在渲染入口（SpriteMixin 的 render/renderNoBind/
 *       renderRegion 覆写、本类的 renderWithCorners HEAD 注入与 glTexCoord2f
 *       重定向）惰发现：贴图已入图集而未重映射时以当前 UV 为原始基准即时
 *       重映射，并输出 {@link AtlasRenderDiag} 带调用栈的溯源日志
 *       （每路径+方法一次）。命中判定按贴图路径缓存，未入图集的 UI 精灵
 *       每帧零 map 查询。</li>
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

    // ── 访问器换算参数（模组经 setTexX/setTexWidth 等访问器读写 UV 时原始↔图集透明换算）──
    /** 图集区域原点 U（regionX / atlasSize）。 */
    @Unique
    private transient float ssoptimizer$atlasOriginU;
    /** 图集区域原点 V（regionY / atlasSize）。 */
    @Unique
    private transient float ssoptimizer$atlasOriginV;
    /** 原始空间→图集空间 U 缩放（srcW / atlasSize）。 */
    @Unique
    private transient float ssoptimizer$atlasScaleU;
    /** 原始空间→图集空间 V 缩放（srcH / atlasSize）。 */
    @Unique
    private transient float ssoptimizer$atlasScaleV;

    // ── 惰性治愈判定缓存（渲染路径上陈旧实例的图集命中判定，见 ssoptimizer$ensureAtlasRemapped）──
    /** 上次判定「贴图是否已入图集」时的贴图路径。 */
    @Unique
    private transient String ssoptimizer$diagCheckedPath;
    /** diagCheckedPath 对应的判定结果（true=已入图集，渲染时必须重映射）。 */
    @Unique
    private transient boolean ssoptimizer$diagAtlased;

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
     * @reason renderWithCorners 是未被覆写的原版渲染方法（装配界面舰船/武器图标
     * 渲染热点路径），直接读 UV 字段并经 texture.bind()（重定向到图集页）采样；
     * 入口惰性治愈陈旧未重映射实例（见 ssoptimizer$ensureAtlasRemapped）。
     */
    @Inject(method = "renderWithCorners(FFFFFFFF)V", at = @At("HEAD"), remap = false)
    private void ssoptimizer$healBeforeRenderWithCorners(final float x1, final float y1,
                                                         final float x2, final float y2,
                                                         final float x3, final float y3,
                                                         final float x4, final float y4,
                                                         final CallbackInfo ci) {
        ssoptimizer$ensureAtlasRemapped("renderWithCorners");
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
        if (this.ssoptimizer$ensureAtlasRemapped("texCoordOriginFixup")) {
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
     * 渲染路径入口的惰性治愈（{@link AtlasUvState#ssoptimizer$ensureAtlasRemapped} 契约）：
     * 图集构建前创建并被缓存的陈旧精灵此后不再经历 setTexture，永远停留在未重映射
     * 状态；渲染时其贴图绑定已重定向到图集页，原始空间 UV 会采样整页——缩略图
     * 串图/条纹的根因类别。此处以当前 UV 为原始基准即时重映射，并输出
     * {@link AtlasRenderDiag} 溯源日志（每路径+方法一次）。
     * <p>
     * 判定缓存：图集命中判定按贴图路径缓存（未入图集的 UI 精灵每帧零 map 查询）；
     * 加载期（图集尚未构建）不缓存未命中判定，避免「加载期未命中」遮蔽
     * 「加载后入图集」的实例。
     */
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
            // 仅缓存命中判定与「图集已构建后的未命中」（加载期未命中可能是图集未构建）
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
        // 陈旧实例：UV 四元组仍是原始纹理空间（原版语义），以此为基准惰性重映射
        AtlasRenderDiag.warnUnremappedSample(path, renderMethod,
                this.texX, this.texY, this.texWidth, this.texHeight);
        this.ssoptimizer$atlasRemapped = this.ssoptimizer$cacheOriginAndRemap(this.texture);
        return this.ssoptimizer$atlasRemapped;
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
        // 访问器换算参数：模组经 setTexX/setTexWidth 等写入的原始空间值以此为基准换算
        this.ssoptimizer$atlasOriginU = uv.originU();
        this.ssoptimizer$atlasOriginV = uv.originV();
        this.ssoptimizer$atlasScaleU = uv.scaleU();
        this.ssoptimizer$atlasScaleV = uv.scaleV();
        // 记录本次产出的图集 texX/texY：下次 setTexture 判定 texX/texY 是否被
        // setTexX/setTexY 改动过（未改动才走幂等路径）
        this.ssoptimizer$atlasLastTexX = this.texX;
        this.ssoptimizer$atlasLastTexY = this.texY;
        return true;
    }

    // ── UV 访问器覆写：图集化后对模组保持原始纹理空间语义 ────────────────────
    // 原版语义下 UV 域就是整张独立纹理，模组（实机案例：LWE 机甲 lwe_gear_control
    // 逐帧 setTexWidth(0/1.0) 切换武器显隐与换臂动画）读写的都是原始空间值；
    // 不换算的话写入值会以图集页为采样域——texWidth=1.0 采满整页宽度（串图/
    // 拉伸条纹），getTexWidth 读到图集值回写则二次缩放。覆写后读写均为原始空间，
    // 内部渲染路径（render/renderRegion/renderWithCorners 等）直接读字段，
    // 不经访问器，不受换算影响。

    /**
     * @author KasumiNova
     * @reason 模组按原版语义写原始空间 UV，图集化后必须换算进区域。
     */
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

    /**
     * @author KasumiNova
     * @reason 同 setTexX。
     */
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

    /**
     * @author KasumiNova
     * @reason 同 setTexX（跨度量无原点）。
     */
    @Overwrite(remap = false)
    public void setTexWidth(final float width) {
        if (this.ssoptimizer$atlasRemapped) {
            this.ssoptimizer$atlasOriginTexWidth = width;
            this.texWidth = AtlasUvMapper.originalToAtlas(0.0F, this.ssoptimizer$atlasScaleU, width);
        } else {
            this.texWidth = width;
        }
    }

    /**
     * @author KasumiNova
     * @reason 同 setTexX（跨度量无原点）。
     */
    @Overwrite(remap = false)
    public void setTexHeight(final float height) {
        if (this.ssoptimizer$atlasRemapped) {
            this.ssoptimizer$atlasOriginTexHeight = height;
            this.texHeight = AtlasUvMapper.originalToAtlas(0.0F, this.ssoptimizer$atlasScaleV, height);
        } else {
            this.texHeight = height;
        }
    }

    /**
     * @author KasumiNova
     * @reason 读取器与 setter 换算对称，模组读到原始空间值（读写往返恒等）。
     */
    @Overwrite(remap = false)
    public float getTexX() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(
                    this.ssoptimizer$atlasOriginU, this.ssoptimizer$atlasScaleU, this.texX);
        }
        return this.texX;
    }

    /**
     * @author KasumiNova
     * @reason 同 getTexX。
     */
    @Overwrite(remap = false)
    public float getTexY() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(
                    this.ssoptimizer$atlasOriginV, this.ssoptimizer$atlasScaleV, this.texY);
        }
        return this.texY;
    }

    /**
     * @author KasumiNova
     * @reason 同 getTexX（跨度量无原点）。
     */
    @Overwrite(remap = false)
    public float getTexWidth() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(0.0F, this.ssoptimizer$atlasScaleU, this.texWidth);
        }
        return this.texWidth;
    }

    /**
     * @author KasumiNova
     * @reason 同 getTexX（跨度量无原点）。
     */
    @Overwrite(remap = false)
    public float getTexHeight() {
        if (this.ssoptimizer$atlasRemapped) {
            return AtlasUvMapper.atlasToOriginal(0.0F, this.ssoptimizer$atlasScaleV, this.texHeight);
        }
        return this.texHeight;
    }
}
