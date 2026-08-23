package github.kasuminova.ssoptimizer.asm.loading;

import java.util.Map;
import java.util.Set;

/**
 * 目标类：{@code org/boxutil/config/BoxConfigGUI}（BoxUtil 模组 BoxUtilMod.jar）。<br>
 * 注入位置：{@code init}/{@code initBackgroundTex} 的 3 处 glTexImage2D 与
 * {@code renderInUICoords} 的 glCopyTexImage2D（GUI 背景屏幕拷贝，初始分配点才计，
 * 每帧 glCopyTexSubImage2D 不产生新分配不埋点）→ screenRT 分类转发钩子。<br>
 * 注入动机：GL 显存账本 screenRT 分类——BoxUtil 配置 GUI 的屏幕尺寸背景纹理旁路
 * （RGBA8、尺寸随 screenFix 缩放，静态扫描确认）。<br>
 * 计量口径：宽×高×bytesPerPixel；纹理 id 取分配时当前绑定（调用点前必有
 * glBindTexture，javap 已核实）。<br>
 * 删除对称性：本类无 glDeleteTextures 路径，<b>只计分配峰值</b>
 * （GUI 纹理全局长存，峰值即实况）。<br>
 * <p>
 * 为什么不用 Mixin：见 {@link GlAllocRedirectProcessor} 类 javadoc。
 */
public final class BoxConfigGuiLedgerProcessor extends GlAllocRedirectProcessor {

    public static final String TARGET_CLASS = "org/boxutil/config/BoxConfigGUI";

    private static final Set<String> TARGETS = Set.of(TARGET_CLASS);
    private static final Map<String, String> REDIRECTS = Map.of(
            "org/lwjgl/opengl/GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
            "rtTexImage2D",
            "org/lwjgl/opengl/GL11.glCopyTexImage2D(IIIIIIII)V", "rtCopyTexImage2D");

    @Override
    protected Set<String> targetClasses() {
        return TARGETS;
    }

    @Override
    protected Map<String, String> redirects() {
        return REDIRECTS;
    }
}
