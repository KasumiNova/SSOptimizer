package github.kasuminova.ssoptimizer.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 精灵 UV 四元组（{@code com.fs.graphics.Sprite}）的 Mixin Accessor。
 *
 * <p>注入目标：{@code com.fs.graphics.Sprite}<br>
 * 注入动机：战役引擎辉光的 hitGlow 合批渲染（{@code CampaignEngineGlowRenderHelper}）
 * 需要把精灵的 UV 直接烘焙进批量顶点缓冲。{@code SpriteAtlasMixin} 会把精灵重映射进
 * 图集，UV 不再是 0..1，必须读取图集空间的真实 texX/texY/texWidth/texHeight；
 * 该四字段为 protected，合批 helper 所在包不可直接访问。<br>
 * 注入效果：暴露 4 个只读 Accessor，供合批 helper 与单测的等价实现复用。</p>
 */
@Mixin(targets = "com.fs.graphics.Sprite")
public interface SpriteUvAccessor {
    /** 精灵 UV 原点 U（图集重映射后为图集 GL 空间值）。 */
    @Accessor(value = "texX", remap = false)
    float ssoptimizer$getTexX();

    /** 精灵 UV 原点 V（图集重映射后为图集 GL 空间值）。 */
    @Accessor(value = "texY", remap = false)
    float ssoptimizer$getTexY();

    /** 精灵 UV 宽度（图集重映射后为图集 GL 空间值）。 */
    @Accessor(value = "texWidth", remap = false)
    float ssoptimizer$getTexWidth();

    /** 精灵 UV 高度（图集重映射后为图集 GL 空间值）。 */
    @Accessor(value = "texHeight", remap = false)
    float ssoptimizer$getTexHeight();
}
