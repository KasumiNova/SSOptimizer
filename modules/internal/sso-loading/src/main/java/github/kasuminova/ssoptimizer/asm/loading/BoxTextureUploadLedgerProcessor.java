package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code org/boxutil/manager/TextureManager}（BoxUtil 模组 BoxUtilMod.jar）。<br>
 * 注入位置：全类范围内把 BoxUtil 贴图统一上传头的 GL 分配调用重定向到
 * {@code GlLedgerHooks} 转发钩子：
 * <ul>
 * <li>{@code loadTexture}/{@code loadTangentMap} 的 glTexImage1D/2D（levels=0 单层）与
 * glTexStorage1D/2D（levels 实参）→ upTex 分类；</li>
 * <li>{@code deleteTexture}/{@code deleteAutoGenNormal} 的 glDeleteTextures → 对称减量。</li>
 * </ul>
 * 注入动机：GL 显存账本 upTex 分类的最大来源——BoxUtil 全部法线/材质/切线贴图
 * 都经此上传（静态扫描确认，texStorage 优先 + glTexImage 回退，levels=1 无 mip 链）。<br>
 * 计量口径：宽×高×bytesPerPixel(internalformat)，levels>1 时按完整 mip 链 ×4/3 近似；
 * 纹理 id 取分配时当前绑定（调用点之前必有 glBindTexture，javap 已核实）。<br>
 * 已知不准：auto-gen 法线图经 putAutoGenNormal 登记的 id 若由 LegacyNormalMapHelper
 * 之外的生成器产生则只被删除路径覆盖（删除时无登记即 no-op，不会负漂）。<br>
 * 删除对称性：deleteTexture/deleteAutoGenNormal 存在，已对称挂 remove。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class BoxTextureUploadLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS = "org/boxutil/manager/TextureManager";

    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL42 = "org/lwjgl/opengl/GL42";
    private static final String TEXIMAGE1D_DESC = "(IIIIIIILjava/nio/ByteBuffer;)V";
    private static final String TEXIMAGE2D_DESC = "(IIIIIIIILjava/nio/ByteBuffer;)V";
    private static final String TEXSTORAGE1D_DESC = "(IIII)V";
    private static final String TEXSTORAGE2D_DESC = "(IIIII)V";
    private static final String DELETE_TEX_DESC = "(I)V";

    private static final Set<String> TARGETS = Set.of(TARGET_CLASS);
    private static final Map<String, String> REDIRECTS = Map.of(
            GL11 + ".glTexImage1D" + TEXIMAGE1D_DESC, "upTexImage1D",
            GL11 + ".glTexImage2D" + TEXIMAGE2D_DESC, "upTexImage2D",
            GL42 + ".glTexStorage1D" + TEXSTORAGE1D_DESC, "upTexStorage1D",
            GL42 + ".glTexStorage2D" + TEXSTORAGE2D_DESC, "upTexStorage2D",
            GL11 + ".glDeleteTextures" + DELETE_TEX_DESC, "upTexDeleteTexture");

    @Override
    protected Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    protected Map<String, String> redirects() {
        return REDIRECTS;
    }
}
