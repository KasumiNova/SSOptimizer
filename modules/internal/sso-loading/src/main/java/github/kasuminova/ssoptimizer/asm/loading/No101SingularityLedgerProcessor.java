package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code data/scripts/combat/No101_SingularityRenderer}（No101 模组）。<br>
 * 注入位置：{@code ensureScreenTexture} 的 glTexImage2D（RGBA8、viewport 尺寸）
 * → screenRT 分类转发钩子；{@code cleanup} 的 glDeleteTextures → 对称减量。<br>
 * 注入动机：GL 显存账本 screenRT 分类——与 Moci_SingularityRenderer 同源的
 * 屏幕尺寸 RT 旁路（字节码级同构，静态扫描确认）。<br>
 * 计量口径与 {@link MociSingularityLedgerProcessor} 相同；删除对称性：cleanup
 * 存在 glDeleteTextures，已对称挂 remove。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class No101SingularityLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS = "data/scripts/combat/No101_SingularityRenderer";

    private static final Set<String> TARGETS = Set.of(TARGET_CLASS);
    private static final Map<String, String> REDIRECTS = Map.of(
            "org/lwjgl/opengl/GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            "rtTexImage2D",
            "org/lwjgl/opengl/GL11.glDeleteTextures(I)V", "rtDeleteTexture");

    @Override
    protected Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    protected Map<String, String> redirects() {
        return REDIRECTS;
    }
}
