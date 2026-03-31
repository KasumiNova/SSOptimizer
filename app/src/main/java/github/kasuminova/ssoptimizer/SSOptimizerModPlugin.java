package github.kasuminova.ssoptimizer;

import com.fs.starfarer.api.BaseModPlugin;
import github.kasuminova.ssoptimizer.loading.ImageIoConfigurator;
import github.kasuminova.ssoptimizer.loading.LazyTextureManager;
import github.kasuminova.ssoptimizer.logging.LogNoiseFilterConfigurator;
import org.apache.log4j.Logger;

public class SSOptimizerModPlugin extends BaseModPlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerModPlugin.class);

    @Override
    public void onApplicationLoad() throws Exception {
        ImageIoConfigurator.configure();
        LogNoiseFilterConfigurator.configure();
        LazyTextureManager.installCompositionReportHookIfConfigured();
        LOGGER.info("[SSOptimizer] Loaded on Java " + Runtime.version());
    }
}