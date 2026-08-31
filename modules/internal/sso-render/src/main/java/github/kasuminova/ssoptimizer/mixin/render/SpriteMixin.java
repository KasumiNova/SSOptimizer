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

/**
 * 单精灵（Sprite）渲染的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.Sprite#render(FF)} 与 {@code renderNoBind(FF)}<br>
 * 注入动机：原版渲染路径包含 17–19 次 LWJGL JNI 调用（颜色、矩阵、纹理坐标、顶点）；
 * 通过整体替换为 {@link SpriteRenderHelper#renderSprite} 的单次 native/GL 调用，
 * 显著降低单精灵渲染开销。<br>
 * 注入效果：两个方法体替换为「null 检查 →（render 仅）纹理绑定 → texClamp 开关 →
 * 字段解包调用 helper → texClamp 恢复」，语义与原 ASM 替换完全一致。
 * 纹理 id 一律经 {@link AtlasTextureResolver} 解析：已入图集的贴图取图集页 id
 * （UV 已由 SpriteAtlasMixin 重映射），未入图集走原版 getTextureId 惰性上传——
 * 图集注入收敛在 Sprite 渲染方法内，{@code TextureObject.getTextureId()} 的其余
 * 调用方（模组裸 UV 消费者）始终拿到独立纹理 id。
 */
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

    /**
     * 带纹理绑定的精灵渲染。
     *
     * @param x 目标 X（叠加 offsetX）
     * @param y 目标 Y（叠加 offsetY）
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 render(FF)V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void render(float x, float y) {
        if (texture == null) {
            return;
        }
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
        // 纹理绑定编码进顶点流（段间执行）：同纹理连续 sprite 的重复绑定回放
        // 幂等冗余，换纹理的绑定打断的是流内位置而非流段——连续 sprite 的
        // begin..end 段合并为一条流命令（减少每 sprite 一次的 flush 边界）
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

    /**
     * 区域渲染（武器炮管、损伤贴花等子矩形绘制）。
     * <p>
     * 原版为 17-19 次 LWJGL JNI（bind/pushMatrix/rotate/立即模式 quad）；覆写后优先并入
     * {@link SpriteBatch} 合批，拒绝时走 {@link SpriteRenderHelper} 单 JNI 路径。
     * 几何等价变换：原版「平移到 (f,g) + 绕全图枢轴旋转 + 子矩形顶点/UV」等价于
     * 「子矩形 quad + 枢轴平移 (pivot - h*w, i*h_) + 子矩形 UV」，
     * 因此可直接复用整图渲染的 helper/合批接口。图集重映射精灵的 UV 补区域原点偏移，
     * 边缘内缩取 {@link SpriteAtlasMixin} 缓存的像素等价值（未重映射保持原版 0.001F
     * 且不补 texX/texY，忠实保留原版忽略 setTexX 的行为）。
     *
     * @param f  目标 X（叠加 offsetX）
     * @param g  目标 Y（叠加 offsetY）
     * @param h  区域 UV 起点 U 比例（0..1）
     * @param i  区域 UV 起点 V 比例
     * @param j  区域 UV 宽比例
     * @param k  区域 UV 高比例
     * @param bl 是否绑定纹理
     * @author KasumiNova
     * @reason 武器炮管 renderBarrel 每帧经 renderRegion 全量重绘（含 GraphicLib 三 pass），
     * 原版立即模式开销显著；需与 render 一样接入合批/单 JNI 路径。
     */
    @Overwrite(remap = false)
    public void renderRegion(float f, float g, float h, float i, float j, float k, boolean bl) {
        if (texture == null) {
            return;
        }
        final AtlasUvState atlas = (AtlasUvState) this;
        final boolean remapped = atlas.ssoptimizer$isAtlasRemapped();
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

        // 原版旋转枢轴：centerX/centerY 有效时用其值，否则取全图中心
        final boolean hasCenter = centerX != -1.0F && centerY != -1.0F;
        final float pivotX = hasCenter ? centerX : width * 0.5F;
        final float pivotY = hasCenter ? centerY : height * 0.5F;
        final float subW = j * width;
        final float subH = k * height;
        // 子矩形 quad 的位置与枢轴（换算到 helper 的「quad 左下角 + 局部枢轴」约定）
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
        // 原版 renderRegion 不处理 texClamp，合批提交同样按非 clamp 处理
        if (SpriteBatch.getInstance().submitIfActive(AtlasTextureResolver.textureIdForSpriteRender(texture),
                posX, posY, subW, subH, subCenterX, subCenterY, angle,
                r, gc, b, a, blendSrc, blendDest,
                u0, v0, u1 - u0, v1 - v0, false)) {
            return;
        }
        if (bl) {
            // 流内绑定（段间执行），语义同 render 的 streamBindTexture
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

    /**
     * 不绑定纹理的精灵渲染（调用方已保证纹理绑定状态）。
     *
     * @param x 目标 X（叠加 offsetX）
     * @param y 目标 Y（叠加 offsetY）
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 renderNoBind(FF)V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void renderNoBind(float x, float y) {
        if (texture == null) {
            return;
        }
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
