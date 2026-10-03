package github.kasuminova.ssoptimizer.common.render.atlas;

import org.apache.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图集渲染诊断：捕获「未重映射精灵采样图集纹理」的陈旧实例并输出可溯源日志。
 * <p>
 * 动机：贴图入图集后，其纹理绑定一律重定向到图集页
 * （{@code LazyTextureManager.bindTexture}），Sprite 的 UV 必须已重映射进图集空间。
 * 图集构建（{@code ResourceLoaderState.init} 返回点）之前创建并被缓存的 Sprite
 * 实例永远停留在未重映射状态（此后不再经历 setTexture），渲染时以原始空间 UV
 * 采样整页图集——缩略图串图/条纹的根因类别。由于实例持有者分散（模组缓存、
 * UI 缓存等）且不可枚举，在渲染路径上惰发现：首次命中时输出带调用栈的日志
 * （每贴图路径 + 渲染方法只记一次），随后由 {@code SpriteAtlasMixin} 惰性治愈。
 */
public final class AtlasRenderDiag {

    private static final Logger LOGGER = Logger.getLogger(AtlasRenderDiag.class);

    /** 已记录过日志的「贴图路径|渲染方法」组合（warn-once）。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private AtlasRenderDiag() {
    }

    /**
     * 记录一次「未重映射精灵采样图集纹理」事件（每路径+方法仅首次输出，带调用栈）。
     *
     * @param texturePath  贴图路径
     * @param renderMethod 触发渲染的方法名（render/renderRegion/renderWithCorners 等）
     * @param texX         采样时的 texX（原始空间）
     * @param texY         采样时的 texY
     * @param texWidth     采样时的 texWidth
     * @param texHeight    采样时的 texHeight
     */
    public static void warnUnremappedSample(final String texturePath, final String renderMethod,
                                            final float texX, final float texY,
                                            final float texWidth, final float texHeight) {
        if (!LOGGED.add(texturePath + '|' + renderMethod)) {
            return;
        }
        LOGGER.warn("[SSOptimizer][AtlasDiag] 未重映射精灵采样图集纹理（将惰性重映射治愈）：path="
                + texturePath + " render=" + renderMethod
                + " uv=(" + texX + "," + texY + "," + texWidth + "," + texHeight + ")",
                new Throwable("AtlasDiag 调用栈溯源"));
    }

    /** 已输出的诊断日志条数（测试用）。 */
    public static int loggedCount() {
        return LOGGED.size();
    }

    /** 清空 warn-once 记录（测试用）。 */
    public static void resetForTest() {
        LOGGED.clear();
    }
}
