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
        texture.bind();
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
