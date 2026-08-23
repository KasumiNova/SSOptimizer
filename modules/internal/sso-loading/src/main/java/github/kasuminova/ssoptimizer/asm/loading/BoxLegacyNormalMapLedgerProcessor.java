package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code org/boxutil/helper/legacy/LegacyNormalMapHelper}（BoxUtil 模组
 * BoxUtilMod.jar）。<br>
 * 注入位置：{@code glPutSourceTexture} 的 2 处 glTexImage2D 与 {@code lambda$new$0/1}
 * 的 glTexStorage2D（GL42/ARB 两变体）→ upTex 分类转发钩子。<br>
 * 注入动机：GL 显存账本 upTex 分类——legacy 法线图逐张整图上传路径，
 * 量级随启用的舰船法线贴图数线性增长（静态扫描确认）。<br>
 * 计量口径：宽×高×bytesPerPixel，texStorage levels>1 按 ×4/3 近似；
 * 纹理 id 取分配时当前绑定（调用点之前必有 glBindTexture，javap 已核实）。<br>
 * 删除对称性：本类无 glDeleteTextures 路径（仅 glDeleteFramebuffers），
 * <b>只计分配峰值</b>；同 id 重分配仍按替换计。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class BoxLegacyNormalMapLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS = "org/boxutil/helper/legacy/LegacyNormalMapHelper";

    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL42 = "org/lwjgl/opengl/GL42";
    private static final String ARB_TS = "org/lwjgl/opengl/ARBTextureStorage";
    private static final String TEXIMAGE2D_DESC = "(IIIIIIIILjava/nio/ByteBuffer;)V";
    private static final String TEXSTORAGE2D_DESC = "(IIIII)V";

    private static final Set<String> TARGETS = Set.of(TARGET_CLASS);
    private static final Map<String, String> REDIRECTS = Map.of(
            GL11 + ".glTexImage2D" + TEXIMAGE2D_DESC, "upTexImage2D",
            GL42 + ".glTexStorage2D" + TEXSTORAGE2D_DESC, "upTexStorage2D",
            ARB_TS + ".glTexStorage2D" + TEXSTORAGE2D_DESC, "upTexStorage2DARB");

    @Override
    protected Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    protected Map<String, String> redirects() {
        return REDIRECTS;
    }
}
