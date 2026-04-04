package github.kasuminova.ssoptimizer;

import com.fs.starfarer.api.BaseModPlugin;
import github.kasuminova.ssoptimizer.common.loading.ImageIoConfigurator;
import github.kasuminova.ssoptimizer.common.loading.LazyTextureManager;
import github.kasuminova.ssoptimizer.common.loading.TextureConversionCache;
import github.kasuminova.ssoptimizer.common.logging.LogNoiseFilterConfigurator;
import org.apache.log4j.Logger;

/**
 * SSOptimizer 的 Starsector Mod 插件入口。
 *
 * <p>在游戏加载 Mod 时由引擎回调 {@link #onApplicationLoad()}，
 * 负责初始化 ImageIO 配置、日志降噪过滤器和延迟纹理管理器等运行时组件。</p>
 */
public class SSOptimizerModPlugin extends BaseModPlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerModPlugin.class);

    @Override
    public void onApplicationLoad() throws Exception {
        ImageIoConfigurator.configure();
        LogNoiseFilterConfigurator.configure();
        TextureConversionCache.warmupMemoryCache();
        LazyTextureManager.installCompositionReportHookIfConfigured();
        LOGGER.info("[SSOptimizer] Loaded on Java " + Runtime.version());
    }
}