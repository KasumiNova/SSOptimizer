package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.LazyTextureManager;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 纹理对象绑定方法的 Mixin 重写，支持延迟纹理加载（Lazy Texture）。
 * <p>
 * 注入目标：{@code com.fs.graphics.TextureObject#bind()} 与 {@code getTextureId()}<br>
 * 注入动机：游戏的纹理绑定逻辑不支持按需加载和贴图合并；
 * 需要在绑定时插入 {@link LazyTextureManager} 的代理调用以实现纹理延迟加载和合并纹理集。<br>
 * 注入效果：{@code bind()} 替换为 {@code LazyTextureManager.bindTexture()}，
 * {@code getTextureId()} 替换为 {@code LazyTextureManager.getTextureId()}。
 */
@Mixin(targets = GameClassNames.TEXTURE_OBJECT_DOTTED)
public abstract class TextureObjectMixin {

    @Shadow(remap = false, aliases = "bindTarget")
    private int ssoptimizer$bindTarget;

    @Shadow(remap = false, aliases = "textureId")
    private int ssoptimizer$textureId;

    /**
     * 将纹理绑定委托给延迟纹理管理器。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 bind()V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void bind() {
        LazyTextureManager.bindTexture((com.fs.graphics.TextureObject) (Object) this, ssoptimizer$bindTarget);
    }

    /**
     * 将纹理 ID 读取委托给延迟纹理管理器。
     *
     * @return 当前纹理 ID（可能触发延迟上传后返回）
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 getTextureId()I 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public int getTextureId() {
        return LazyTextureManager.getTextureId(
                (com.fs.graphics.TextureObject) (Object) this, ssoptimizer$bindTarget, ssoptimizer$textureId);
    }
}
