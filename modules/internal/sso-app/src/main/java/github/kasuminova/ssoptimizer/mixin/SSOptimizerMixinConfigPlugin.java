package github.kasuminova.ssoptimizer.mixin;

import github.kasuminova.ssoptimizer.common.render.ShipEngineRenderOptimizationToggle;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * SSOptimizer Mixin 配置插件。
 * <p>
 * 根据 JVM 参数过滤默认关闭的实验性/高风险 Mixin：
 * <ul>
 *   <li>{@code render.EngineRenderMixin} 仅在 {@code -Dssoptimizer.render.shipengine.enable=true}
 *       显式启用时生效，不影响 Sprite、字体、粒子等其他渲染优化；</li>
 *   <li>{@code render.SpriteMixin} 与 {@code render.BitmapFontRendererMixin} 硬依赖 RT 模式的
 *       bridge 顶点流/上传通道，{@code -Dssoptimizer.renderthread.enable=false} 时整体禁用
 *       （回退原版渲染路径，否则 bridge 未安装即 ISE 崩溃）；</li>
 *   <li>{@code mixin.ai} 包整套并行 AI 织入由 {@link #AI_PARALLEL_DISABLE_PROPERTY} 同生共死。</li>
 * </ul>
 */
public final class SSOptimizerMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerMixinConfigPlugin.class);

    /** 并行 AI 织入总开关（禁用整个 mixin.ai 包）。 */
    public static final String AI_PARALLEL_DISABLE_PROPERTY = "ssoptimizer.disable.aiparallel";

    /**
     * Mixin 配置加载回调。
     *
     * @param mixinPackage 配置中的 Mixin 包名
     */
    @Override
    public void onLoad(final String mixinPackage) {
    }

    /**
     * 返回用于引用查找的 refmap 配置。
     *
     * @return 当前项目不使用 refmap，返回 null
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * 判断指定 Mixin 是否应应用到目标类。
     *
     * @param targetClassName 目标类名
     * @param mixinClassName  Mixin 类名
     * @return 允许应用返回 true，否则返回 false
     */
    @Override
    public boolean shouldApplyMixin(final String targetClassName,
                                    final String mixinClassName) {
        if (mixinClassName.endsWith(".render.EngineRenderMixin") && !ShipEngineRenderOptimizationToggle.isEnabled()) {
            LOGGER.info("[SSOptimizer] Ship engine render optimization mixin disabled by default; enable with -D"
                + ShipEngineRenderOptimizationToggle.ENABLE_PROPERTY + "=true");
            return false;
        }
        // RT 关闭回退路径：bridge 不安装（RenderThreadRedirectTransformer no-op），
        // 硬依赖 bridge 顶点流/上传通道的 Mixin 必须整体禁用，否则首个调用点即以
        // 「RenderQueue 未安装」ISE 崩溃（实机：RT=false 时标题界面字体渲染死于
        // GlyphAtlasPage.ensureTexture → GlDispatch.allocate）：
        // - render.SpriteMixin：覆写体调 bridge GL11.streamBindTexture/stream* 录制
        //   入口，RT 关闭时顶点流永不落帧（无限增长）且语义依赖渲染线程回放；
        // - render.BitmapFontRendererMixin：v2 文本管线（布局引擎 + TextStreamEmitter
        //   流式发射 + TTF 动态图集 GlDispatch 上传）全链路易于 bridge。
        // 禁用后两条路径回退原版渲染，与 RenderThreadMode「回退到旧行为」语义一致。
        if (!RenderThreadMode.isEnabled()
                && (mixinClassName.endsWith(".render.SpriteMixin")
                || mixinClassName.endsWith(".render.BitmapFontRendererMixin"))) {
            LOGGER.info("[SSOptimizer] 渲染线程分离模式已关闭，禁用依赖 bridge 的 Mixin: " + mixinClassName);
            return false;
        }
        // 并行 AI 织入总开关：整套 ai 包 Mixin 同生共死（含线程本地化与并发化），
        // 另有 ssoptimizer.ai.parallel=false 运行期软开关（保留织入、全部内联串行）。
        if (mixinClassName.contains(".mixin.ai.")
                && Boolean.getBoolean(AI_PARALLEL_DISABLE_PROPERTY)) {
            LOGGER.info("[SSOptimizer] Parallel AI mixin disabled via -D" + AI_PARALLEL_DISABLE_PROPERTY
                + "=true: " + mixinClassName);
            return false;
        }
        return true;
    }

    /**
     * 接收 Mixin 目标集合。
     *
     * @param myTargets    当前配置目标集合
     * @param otherTargets 其他配置目标集合
     */
    @Override
    public void acceptTargets(final Set<String> myTargets,
                              final Set<String> otherTargets) {
    }

    /**
     * 返回额外 Mixin 列表。
     *
     * @return 不追加额外 Mixin，返回 null
     */
    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Mixin 应用前回调。
     *
     * @param targetClassName 目标类名
     * @param targetClass     目标类节点
     * @param mixinClassName  Mixin 类名
     * @param mixinInfo       Mixin 信息
     */
    @Override
    public void preApply(final String targetClassName,
                         final ClassNode targetClass,
                         final String mixinClassName,
                         final IMixinInfo mixinInfo) {
    }

    /**
     * Mixin 应用后回调。
     *
     * @param targetClassName 目标类名
     * @param targetClass     目标类节点
     * @param mixinClassName  Mixin 类名
     * @param mixinInfo       Mixin 信息
     */
    @Override
    public void postApply(final String targetClassName,
                          final ClassNode targetClass,
                          final String mixinClassName,
                          final IMixinInfo mixinInfo) {
    }
}