package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code data/scripts/weapons/render/Moci_SingularityRenderer}
 * （MociShipPack 模组）。<br>
 * 注入位置：{@code ensureScreenTexture}/{@code ensureTexture} 的 glTexImage2D
 * （RGBA8、viewport 尺寸的屏幕 RT）→ screenRT 分类转发钩子；
 * {@code cleanup} 等路径的 glDeleteTextures → 对称减量。<br>
 * 注入动机：GL 显存账本 screenRT 分类——模组自建屏幕尺寸 RT 旁路
 * （1080p RGBA8 ≈ 8.3MiB/张，静态扫描确认；render 内每帧 glCopyTexSubImage2D
 * 不产生新分配，不埋点）。<br>
 * 计量口径：宽×高×bytesPerPixel；纹理 id 取分配时当前绑定（glTexImage2D 前必有
 * glBindTexture，javap 已核实）；尺寸变化重建按 id 替换计。<br>
 * 删除对称性：cleanup 存在 glDeleteTextures，已对称挂 remove（同 id 幂等）。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class MociSingularityLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS =
            "data/scripts/weapons/render/Moci_SingularityRenderer";

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
