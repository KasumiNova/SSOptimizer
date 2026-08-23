package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code cn/kasuminova/astd/impl/render/TexTrailRenderer$Plugin}
 * （Asteria Directorate 模组）。<br>
 * 注入位置：{@code createTargetTexture}（viewport 尺寸 RT）与
 * {@code ensureProgram}/{@code uploadTrailTexture} 的 glTexImage2D
 * → screenRT 分类转发钩子；{@code cleanup}/{@code deleteBloomTargets} 的
 * glDeleteTextures → 对称减量。<br>
 * 注入动机：GL 显存账本 screenRT 分类——拖尾渲染器的屏幕尺寸快照 RT 旁路
 * （静态扫描确认：glGenTextures → glBindTexture → glTexImage2D 序列）。<br>
 * 计量口径：宽×高×bytesPerPixel；纹理 id 取分配时当前绑定。<br>
 * 已知不准：uploadTrailTexture 上传的是拖尾贴图本体（BufferedImage 尺寸，非屏幕
 * 尺寸），归入 screenRT 属分类近似；本类的 glBufferData（拖尾顶点 VBO）量级小，
 * 不在本轮 vbo 埋点范围。<br>
 * 删除对称性：cleanup/deleteBloomTargets 存在 glDeleteTextures，已对称挂 remove。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class AstdTexTrailLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS =
            "cn/kasuminova/astd/impl/render/TexTrailRenderer$Plugin";

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
