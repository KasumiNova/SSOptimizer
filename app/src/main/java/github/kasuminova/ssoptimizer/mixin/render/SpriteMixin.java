package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import com.fs.graphics.util.RenderStateUtils;
import github.kasuminova.ssoptimizer.common.render.engine.SpriteRenderHelper;
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
 */
@Mixin(targets = GameClassNames.SPRITE_DOTTED)
public abstract class SpriteMixin {

    @Shadow(remap = false, aliases = "texture")
    private TextureObject ssoptimizer$texture;

    @Shadow(remap = false, aliases = "texClamp")
    private boolean ssoptimizer$texClamp;

    @Shadow(remap = false, aliases = "offsetX")
    private int ssoptimizer$offsetX;

    @Shadow(remap = false, aliases = "offsetY")
    private int ssoptimizer$offsetY;

    @Shadow(remap = false, aliases = "width")
    private float ssoptimizer$width;

    @Shadow(remap = false, aliases = "height")
    private float ssoptimizer$height;

    @Shadow(remap = false, aliases = "centerX")
    private float ssoptimizer$centerX;

    @Shadow(remap = false, aliases = "centerY")
    private float ssoptimizer$centerY;

    @Shadow(remap = false, aliases = "angle")
    private float ssoptimizer$angle;

    @Shadow(remap = false, aliases = "color")
    private Color ssoptimizer$color;

    @Shadow(remap = false, aliases = "alphaMult")
    private float ssoptimizer$alphaMult;

    @Shadow(remap = false, aliases = "blendSrc")
    private int ssoptimizer$blendSrc;

    @Shadow(remap = false, aliases = "blendDest")
    private int ssoptimizer$blendDest;

    @Shadow(remap = false, aliases = "texX")
    private float ssoptimizer$texX;

    @Shadow(remap = false, aliases = "texY")
    private float ssoptimizer$texY;

    @Shadow(remap = false, aliases = "texWidth")
    private float ssoptimizer$texWidth;

    @Shadow(remap = false, aliases = "texHeight")
    private float ssoptimizer$texHeight;

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
        if (ssoptimizer$texture == null) {
            return;
        }
        ssoptimizer$texture.bind();
        if (ssoptimizer$texClamp) {
            RenderStateUtils.enableTextureClamp();
        }
        SpriteRenderHelper.renderSprite(
                x + ssoptimizer$offsetX, y + ssoptimizer$offsetY,
                ssoptimizer$width, ssoptimizer$height,
                ssoptimizer$centerX, ssoptimizer$centerY,
                ssoptimizer$angle,
                ssoptimizer$color.getRed(), ssoptimizer$color.getGreen(), ssoptimizer$color.getBlue(),
                (int) (ssoptimizer$color.getAlpha() * ssoptimizer$alphaMult),
                ssoptimizer$blendSrc, ssoptimizer$blendDest,
                ssoptimizer$texX, ssoptimizer$texY, ssoptimizer$texWidth, ssoptimizer$texHeight);
        if (ssoptimizer$texClamp) {
            RenderStateUtils.restoreTextureClamp();
        }
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
        if (ssoptimizer$texture == null) {
            return;
        }
        if (ssoptimizer$texClamp) {
            RenderStateUtils.enableTextureClamp();
        }
        SpriteRenderHelper.renderSprite(
                x + ssoptimizer$offsetX, y + ssoptimizer$offsetY,
                ssoptimizer$width, ssoptimizer$height,
                ssoptimizer$centerX, ssoptimizer$centerY,
                ssoptimizer$angle,
                ssoptimizer$color.getRed(), ssoptimizer$color.getGreen(), ssoptimizer$color.getBlue(),
                (int) (ssoptimizer$color.getAlpha() * ssoptimizer$alphaMult),
                ssoptimizer$blendSrc, ssoptimizer$blendDest,
                ssoptimizer$texX, ssoptimizer$texY, ssoptimizer$texWidth, ssoptimizer$texHeight);
        if (ssoptimizer$texClamp) {
            RenderStateUtils.restoreTextureClamp();
        }
    }
}
